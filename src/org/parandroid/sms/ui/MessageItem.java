/*
 * Copyright (C) 2008 Esmertec AG.
 * Copyright (C) 2008 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.parandroid.sms.ui;

import java.io.IOException;
import java.security.GeneralSecurityException;

import org.parandroid.encoding.Base64Coder;
import org.parandroid.encryption.MessageEncryption;
import org.parandroid.encryption.MessageEncryptionFactory;
import org.parandroid.sms.R;
import org.parandroid.sms.model.SlideModel;
import org.parandroid.sms.model.SlideshowModel;
import org.parandroid.sms.model.TextModel;
import org.parandroid.sms.ui.MessageListAdapter.ColumnsMap;
import org.parandroid.sms.util.ContactInfoCache;
import com.google.android.mms.MmsException;
import com.google.android.mms.pdu.EncodedStringValue;
import com.google.android.mms.pdu.MultimediaMessagePdu;
import com.google.android.mms.pdu.NotificationInd;
import com.google.android.mms.pdu.PduHeaders;
import com.google.android.mms.pdu.PduPersister;
import com.google.android.mms.pdu.RetrieveConf;
import com.google.android.mms.pdu.SendReq;

import android.content.ContentUris;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.provider.Telephony.Mms;
import android.provider.Telephony.Sms;
import android.provider.Telephony.Threads;
import android.text.TextUtils;
import android.util.Log;

/**
 * Mostly immutable model for an SMS/MMS message.
 *
 * <p>The only mutable field is the cached formatted message member,
 * the formatting of which is done outside this model in MessageListItem.
 */
public class MessageItem {
    private static String TAG = "Parandroid MessageItem";

    public final static int MESSAGE_TYPE_PARANDROID_INBOX = 7;
    public final static int MESSAGE_TYPE_PARANDROID_OUTBOX = 8;
    public final static int MESSAGE_TYPE_PARANDROID_QUEUED = 9;

    final Context mContext;
    final String mType;
    final long mMsgId;
    final int mBoxId;

    boolean mDeliveryReport;
    boolean mReadReport;

    boolean publicKey = false;
    String rawBody;


    String mTimestamp;
    String mAddress;
    String mContact;
    String mBody; // Body of SMS, first text of MMS.

    // The only non-immutable field.  Not synchronized, as access will
    // only be from the main GUI thread.  Worst case if accessed from
    // another thread is it'll return null and be set again from that
    // thread.
    CharSequence mCachedFormattedMessage;

    // Fields for MMS only.
    Uri mMessageUri;
    int mMessageType;
    int mAttachmentType;
    String mSubject;
    SlideshowModel mSlideshow;
    int mMessageSize;
    int mErrorType;
    int mThreadType;

    MessageItem(
            Context context, String type, Cursor cursor,
            ColumnsMap columnsMap, int threadType) throws MmsException {
        mContext = context;
        mThreadType = threadType;
        mMsgId = cursor.getLong(columnsMap.mColumnMsgId);
        
        if ("sms".equals(type)) {
            ContactInfoCache infoCache = ContactInfoCache.getInstance();
            mReadReport = false; // No read reports in sms
            mDeliveryReport = (cursor.getLong(columnsMap.mColumnSmsStatus)
                    != Sms.STATUS_NONE);
            mMessageUri = ContentUris.withAppendedId(Sms.CONTENT_URI, mMsgId);
            // Set contact and message body
            mBoxId = cursor.getInt(columnsMap.mColumnSmsType);
            mAddress = cursor.getString(columnsMap.mColumnSmsAddress);
            if (Sms.isOutgoingFolder(mBoxId) || isEncryptedOutgoingMessage()) {
                String meString = context.getString(
                        R.string.messagelist_sender_self);

                if (mThreadType == Threads.COMMON_THREAD) {
                    mContact = meString;
                } else {
                    mContact = String.format(
                            context.getString(R.string.broadcast_from_to),
                            meString,
                            infoCache.getContactName(context, mAddress));
                }
            } else {
                // For incoming messages, the ADDRESS field contains the sender.
                mContact = infoCache.getContactName(context, mAddress);
            }
            mBody = cursor.getString(columnsMap.mColumnSmsBody);
            
            if(isEncryptedIncomingMessage() || isEncryptedOutgoingMessage()){
	            if(MessageEncryptionFactory.isAuthenticated()){
	            	try {
						mBody = MessageEncryption.decrypt(context, mAddress, Base64Coder.decode(mBody));
					} catch (Exception e) {
						Log.e(TAG, "Error decrypting message");
						e.printStackTrace();
					}
	            }else if(ComposeMessageActivity.encryptIfNeeded && !MessageEncryptionFactory.isAuthenticating()){
	            	ComposeMessageActivity.encryptIfNeeded = false;
	            	MessageEncryptionFactory.setAuthenticating(true);
	            	Intent intent = new Intent(context, AuthenticateActivity.class);
	            	context.startActivity(intent);
	            }
            }
            
            if (!isOutgoingMessage()) {
                // Set "sent" time stamp
                long date = cursor.getLong(columnsMap.mColumnSmsDate);
                mTimestamp = String.format(context.getString(R.string.sent_on),
                        MessageUtils.formatTimeStampString(context, date));
            }

        } else if ("mms".equals(type)) {
            mMessageUri = ContentUris.withAppendedId(Mms.CONTENT_URI, mMsgId);
            mBoxId = cursor.getInt(columnsMap.mColumnMmsMessageBox);
            mMessageType = cursor.getInt(columnsMap.mColumnMmsMessageType);
            mErrorType = cursor.getInt(columnsMap.mColumnMmsErrorType);
            String subject = cursor.getString(columnsMap.mColumnMmsSubject);
            if (!TextUtils.isEmpty(subject)) {
                EncodedStringValue v = new EncodedStringValue(
                        cursor.getInt(columnsMap.mColumnMmsSubjectCharset),
                        PduPersister.getBytes(subject));
                mSubject = v.getString();
            }

            long timestamp = 0L;
            PduPersister p = PduPersister.getPduPersister(mContext);
            if (PduHeaders.MESSAGE_TYPE_NOTIFICATION_IND == mMessageType) {
                mDeliveryReport = false;
                NotificationInd notifInd = (NotificationInd) p.load(mMessageUri);
                interpretFrom(notifInd.getFrom());
                // Borrow the mBody to hold the URL of the message.
                mBody = new String(notifInd.getContentLocation());
                mMessageSize = (int) notifInd.getMessageSize();
                timestamp = notifInd.getExpiry() * 1000L;
            } else {
                MultimediaMessagePdu msg = (MultimediaMessagePdu) p.load(mMessageUri);
                mSlideshow = SlideshowModel.createFromPduBody(context, msg.getBody());
                mAttachmentType = MessageUtils.getAttachmentType(mSlideshow);

                if (mMessageType == PduHeaders.MESSAGE_TYPE_RETRIEVE_CONF) {
                    RetrieveConf retrieveConf = (RetrieveConf) msg;
                    interpretFrom(retrieveConf.getFrom());
                    timestamp = retrieveConf.getDate() * 1000L;
                } else {
                    // Use constant string for outgoing messages
                    mContact = mAddress = context.getString(
                            R.string.messagelist_sender_self);
                    timestamp = ((SendReq) msg).getDate() * 1000L;
                }


                String report = cursor.getString(columnsMap.mColumnMmsDeliveryReport);
                if ((report == null) || !mAddress.equals(context.getString(
                        R.string.messagelist_sender_self))) {
                    mDeliveryReport = false;
                } else {
                    int reportInt;
                    try {
                        reportInt = Integer.parseInt(report);
                        mDeliveryReport =
                            (reportInt == PduHeaders.VALUE_YES);
                    } catch (NumberFormatException nfe) {
                        Log.e(TAG, "Value for delivery report was invalid.");
                        mDeliveryReport = false;
                    }
                }

                report = cursor.getString(columnsMap.mColumnMmsReadReport);
                if ((report == null) || !mAddress.equals(context.getString(
                        R.string.messagelist_sender_self))) {
                    mReadReport = false;
                } else {
                    int reportInt;
                    try {
                        reportInt = Integer.parseInt(report);
                        mReadReport = (reportInt == PduHeaders.VALUE_YES);
                    } catch (NumberFormatException nfe) {
                        Log.e(TAG, "Value for read report was invalid.");
                        mReadReport = false;
                    }
                }

                SlideModel slide = mSlideshow.get(0);
                if ((slide != null) && slide.hasText()) {
                    TextModel tm = slide.getText();
                    if (tm.isDrmProtected()) {
                        mBody = mContext.getString(R.string.drm_protected_text);
                    } else {
                        mBody = slide.getText().getText();
                    }
                }

                mMessageSize = mSlideshow.getCurrentMessageSize();
            }

            mTimestamp = context.getString(getTimestampStrId(),
                    MessageUtils.formatTimeStampString(context, timestamp));
        } else {
            throw new MmsException("Unknown type of the message: " + type);
        }

        mType = type;
    }

    private void interpretFrom(EncodedStringValue from) {
        if (from != null) {
            mAddress = from.getString();
            mContact = ContactInfoCache.getInstance().getContactName(mContext, mAddress);
        } else {
            mContact = mAddress = mContext.getString(
                    R.string.anonymous_recipient);
        }
    }

    private int getTimestampStrId() {
        if (PduHeaders.MESSAGE_TYPE_NOTIFICATION_IND == mMessageType) {
            return R.string.expire_on;
        } else {
            return R.string.sent_on;
        }
    }

    public boolean isMms() {
        return mType.equals("mms");
    }

    public boolean isSms() {
        return mType.equals("sms");
    }

    public boolean isDownloaded() {
        return (mMessageType != PduHeaders.MESSAGE_TYPE_NOTIFICATION_IND);
    }

    public boolean isOutgoingMessage() {
        boolean isOutgoingMms = isMms() && (mBoxId == Mms.MESSAGE_BOX_OUTBOX);
        boolean isOutgoingSms = isSms()
                                    && ((mBoxId == Sms.MESSAGE_TYPE_FAILED)
                                            || (mBoxId == Sms.MESSAGE_TYPE_OUTBOX)
                                            || (mBoxId == Sms.MESSAGE_TYPE_QUEUED));
        return isOutgoingMms || isOutgoingSms || isEncryptedOutgoingMessage();
    }
    
    public boolean isEncryptedIncomingMessage(){
    	return isSms() && mBoxId == MESSAGE_TYPE_PARANDROID_INBOX;
    }
    
    public boolean isEncryptedOutgoingMessage(){
    	return isSms() && mBoxId == MESSAGE_TYPE_PARANDROID_OUTBOX;
    }

    // Note: This is the only mutable field in this class.  Think of
    // mCachedFormattedMessage as a C++ 'mutable' field on a const
    // object, with this being a lazy accessor whose logic to set it
    // is outside the class for model/view separation reasons.  In any
    // case, please keep this class conceptually immutable.
    public void setCachedFormattedMessage(CharSequence formattedMessage) {
        mCachedFormattedMessage = formattedMessage;
    }

    public CharSequence getCachedFormattedMessage() {
        return mCachedFormattedMessage;
    }
}
