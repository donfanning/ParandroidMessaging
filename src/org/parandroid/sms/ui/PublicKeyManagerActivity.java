package org.parandroid.sms.ui;

import java.util.ArrayList;

import org.parandroid.encryption.MessageEncryptionFactory;
import org.parandroid.sms.R;

import android.app.Activity;
import android.os.Bundle;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.ListView;
import android.widget.AdapterView.OnItemClickListener;
import android.view.ContextMenu;
import android.view.ContextMenu.ContextMenuInfo;
import android.widget.AdapterView.AdapterContextMenuInfo;
import android.view.MenuItem;
import android.app.AlertDialog;
import android.content.DialogInterface;

public class PublicKeyManagerActivity extends Activity {

	private static final String TAG = "PublicKeyManagerActivity";
    private static final int CONTEXT_MENU_DELETE = 0;

    private ArrayList<String> publicKeys;

    @Override
    public void onCreate(Bundle savedInstanceState) {
	    super.onCreate(savedInstanceState);
	    setContentView(R.layout.public_key_manager);
	    init();
	}
    
    private void init(){
        final ArrayList<String> items = MessageEncryptionFactory.getPublicKeys(this);
        publicKeys = items;
	    ListView publicKeysList = (ListView) findViewById(R.id.public_keys);
        registerForContextMenu(publicKeysList);
	    final ArrayAdapter<String> publicKeys = new ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, items);
	    publicKeysList.setAdapter(publicKeys);
    }

    public void onCreateContextMenu(ContextMenu menu, View v, ContextMenuInfo menuInfo){
        super.onCreateContextMenu(menu, v, menuInfo);
        menu.add(0, CONTEXT_MENU_DELETE, 0, getString(R.string.delete_public_key));
    }

    public boolean onContextItemSelected(MenuItem item){
        final AdapterContextMenuInfo info = (AdapterContextMenuInfo) item.getMenuInfo();
        switch(item.getItemId()){
            case CONTEXT_MENU_DELETE:
                AlertDialog.Builder generateKeypairDialogBuilder = new AlertDialog.Builder(this);
        	    generateKeypairDialogBuilder.setMessage(getText(R.string.delete_public_key_dialog))
        		   .setTitle(getText(R.string.delete_public_key))
        		   .setCancelable(false)
        	       .setPositiveButton(getText(R.string.yes), new DialogInterface.OnClickListener() {
        	           public void onClick(DialogInterface dialog, int id) {
                            MessageEncryptionFactory.deletePublicKey(PublicKeyManagerActivity.this, publicKeys.get(info.position));
                            init();
        	           }
        	       })
        	       .setNegativeButton(getText(R.string.no), new DialogInterface.OnClickListener() {
        	           public void onClick(DialogInterface dialog, int id) {
        	                dialog.cancel();
        	           }
        	       });
        	
        	AlertDialog alert = generateKeypairDialogBuilder.create();
        	alert.show();

            return true;
            default:
                return super.onContextItemSelected(item);
        }
    }
}