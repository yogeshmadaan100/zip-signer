/*
 * Copyright (C) 2010 Ken Ellinwood.
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
package kellinwood.zipsigner;

import java.util.Observable;
import java.util.Observer;

import kellinwood.zipsigner.R;

import kellinwood.logging.LoggerManager;
import kellinwood.logging.android.AndroidLogger;
import kellinwood.logging.android.AndroidLoggerFactory;
import kellinwood.security.zipsigner.ProgressEvent;
import kellinwood.security.zipsigner.ZipSigner;
import kellinwood.security.zipsigner.ProgressListener;

import android.app.Activity;
import android.content.Intent;

import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;


/** Demo app for signing zip, apk, and/or jar files on an Android device. */
public class ZipSignerActivity extends Activity {


    AndroidLogger logger = null;
    ProgressBar progressBar = null;
    TextView currentItemView = null;
    SignerThread signerThread = null;

    private static final int MESSAGE_TYPE_PERCENT_DONE = 1;
    private static final int MESSAGE_TYPE_SIGNING_COMPLETE = 2;
    private static final int MESSAGE_TYPE_SIGNING_CANCELED = 3;
    private static final int MESSAGE_TYPE_SIGNING_ERROR = 4;    
    private static final int MESSAGE_TYPE_ANNOUNCE_KEY = 5;    

    private static final String MESSAGE_KEY = "message";


    /** Called when the activity is first created. */
    @Override
    public void onCreate(Bundle savedInstanceState) {

        super.onCreate(savedInstanceState);
        setContentView(R.layout.zip_signer);

        LoggerManager.setLoggerFactory( new AndroidLoggerFactory());

        logger = (AndroidLogger)LoggerManager.getLogger(this.getClass().getName());
        logger.setToastContext(getBaseContext());

        currentItemView = (TextView)findViewById(R.id.SigningZipItemTextView);

        progressBar = (ProgressBar)findViewById(R.id.SigningZipProgressBar);
        progressBar.setIndeterminate(false);
        progressBar.setMax(100);
        progressBar.setProgress(0);

        Button cancelButton = (Button)findViewById(R.id.SigningZipCancelButton);
        cancelButton.setOnClickListener( new OnClickListener() {
            public void onClick(View arg0) {
                signerThread.cancel();
            }

        });

        signerThread = new SignerThread( handler, getIntent());
        signerThread.start();
    }

    // Define the Handler that receives messages from the thread and update the progress
    final Handler handler = new Handler() {
        public void handleMessage(Message msg) {
            // int msgType = msg.getData().getInt(MESSAGE_TYPE_NAME);
            switch (msg.what) {
            case MESSAGE_TYPE_PERCENT_DONE:
                int percentDone = msg.arg1; 
                if (percentDone > 100) percentDone = 100;
                progressBar.setProgress(percentDone);
                Bundle data = msg.getData();
                if (data != null) {
                    String currentItem = data.getString( MESSAGE_KEY);
                    currentItemView.setText( currentItem);
                }
                break;
            case MESSAGE_TYPE_SIGNING_COMPLETE:
                progressBar.setProgress(100);
                setResult( RESULT_OK);
                finish();
                break;
            case MESSAGE_TYPE_SIGNING_CANCELED:
                setResult( RESULT_CANCELED);
                finish();
                break;
            case MESSAGE_TYPE_SIGNING_ERROR:
                String msgText = msg.getData().getString( MESSAGE_KEY);
                logger.error( msgText);
                Intent e = new Intent();
                e.putExtra( "errorMessage", msgText);
                setResult( RESULT_FIRST_USER, e);
                finish();
                break;
            case MESSAGE_TYPE_ANNOUNCE_KEY:
                msgText = msg.getData().getString( MESSAGE_KEY);
                // this is not an error, but is used to show a toast to with the key name.
                logger.error("Signing with key: " + msgText);
                break;
            }
        }
    };

    class SignerThread extends Thread implements ProgressListener, Observer
    {
        ZipSigner zipSigner = null;
        Handler mHandler;
        long lastProgressTime = 0;

        String inputFile;
        String outputFile;
        String keyMode;
        boolean showProgressItems;

        private String getStringExtra( Intent i, String extraName, String defaultValue) {

            String value = i.getStringExtra( extraName);
            if (value == null) return defaultValue;
            return value;
        }

        SignerThread(Handler h, Intent i)
        {
            mHandler = h;

            showProgressItems = Boolean.valueOf( getStringExtra(i, "showProgressItems", "true"));
            inputFile = i.getStringExtra("inputFile");
            outputFile = i.getStringExtra("outputFile");
            keyMode = i.getStringExtra("keyMode");
            if (keyMode == null) keyMode = "testkey"; // backwards compatible.
        }


        public void cancel() {
            if (zipSigner != null) zipSigner.cancel();
        }

        public void run()
        {
            try {
                if (inputFile == null) throw new IllegalArgumentException("Parameter inputFile is null");
                if (outputFile == null) throw new IllegalArgumentException("Parameter outputFile is null");

                zipSigner = new ZipSigner();
                zipSigner.setKeymode(keyMode);
                zipSigner.addAutoKeyObserver(this);
                zipSigner.addProgressListener( this);

                zipSigner.signZip( inputFile, outputFile);

                if (zipSigner.isCanceled()) 
                    sendMessage( MESSAGE_TYPE_SIGNING_CANCELED, 0, null, null);
                else
                    sendMessage( MESSAGE_TYPE_SIGNING_COMPLETE, 0, null, null);

            }
            catch (Throwable t) {
                String tname = t.getClass().getName();
                int pos = tname.lastIndexOf('.');
                if (pos >= 0) tname = tname.substring(pos+1);

                sendMessage( MESSAGE_TYPE_SIGNING_ERROR, 0, MESSAGE_KEY, tname + ": " + t.getMessage());
            }
        }

        private void sendMessage( int messageType, int arg1, String str1Name, String str1Value) {
            Message msg = mHandler.obtainMessage();

            msg.what = messageType;
            msg.arg1 = arg1;
            if (str1Name != null) {
                Bundle b = new Bundle();
                b.putString(str1Name, str1Value);
                msg.setData(b);
            }
            mHandler.sendMessage(msg);
        }


        /** Called to notify the listener that progress has been made during
            the zip signing operation.
            @param currentItem the name of the item being processed.
            @param percentDone a value between 0 and 100 indicating 
            percent complete.
         */
        public void onProgress( ProgressEvent event)
        {
            long currentTime = System.currentTimeMillis();

            // Update progress at most twice a second but always display 100%.
            if (event.getPercentDone() == 100 || event.getPriority() > ProgressEvent.PRORITY_NORMAL || (currentTime - lastProgressTime) >= 500)
            {
                if (showProgressItems)
                    sendMessage( MESSAGE_TYPE_PERCENT_DONE, event.getPercentDone(), MESSAGE_KEY, event.getMessage());
                else
                    sendMessage( MESSAGE_TYPE_PERCENT_DONE, event.getPercentDone(), null, null);

                lastProgressTime = currentTime;
            }
        }

        // Called when the key is automatically determined
        @Override
        public void update(Observable o, Object arg) {
            logger.debug("observer update: " + arg);
            sendMessage( MESSAGE_TYPE_ANNOUNCE_KEY, 0, MESSAGE_KEY, (String)arg);
            
        }
    }

}