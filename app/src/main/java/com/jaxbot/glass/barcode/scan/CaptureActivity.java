/*
 * Copyright (C) 2008 ZXing authors
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.jaxbot.glass.barcode.scan;

// Adjust to whatever the main package name is

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;

import com.google.android.glass.widget.CardScrollAdapter;
import com.google.android.glass.widget.CardScrollView;
import com.google.zxing.DecodeHintType;
import com.google.zxing.Result;
import com.google.zxing.ResultMetadataType;
import com.google.zxing.client.android.camera.CameraManager;
import com.google.zxing.client.result.ParsedResult;
import com.google.zxing.client.result.ResultParser;
import ge.avioaero.aviation.quiricode.BaseGlassActivity;
import com.jaxbot.glass.barcode.migrated.BeepManager;
import com.jaxbot.glass.barcode.migrated.InactivityTimer;
import com.jaxbot.glass.barcode.scan.ui.ViewfinderView;
import ge.avioaero.aviation.quiricode.MainActivity;
import ge.avioaero.aviation.quiricode.R;

import java.io.IOException;
import java.util.Collection;
import java.util.EnumSet;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;

/**
 * This activity opens the camera and does the actual scanning on a background
 * thread. It draws a
 * viewfinder to help the user place the barcode correctly, shows feedback as
 * the image processing
 * is happening, and then overlays the results when a scan is successful.
 *
 * @author dswitkin@google.com (Daniel Switkin)
 * @author Sean Owen
 */
public final class CaptureActivity extends BaseGlassActivity implements
        SurfaceHolder.Callback {

    private static final String TAG = CaptureActivity.class.getSimpleName();

    private static final Collection<ResultMetadataType> DISPLAYABLE_METADATA_TYPES = EnumSet
            .of(ResultMetadataType.ISSUE_NUMBER,
                    ResultMetadataType.SUGGESTED_PRICE,
                    ResultMetadataType.ERROR_CORRECTION_LEVEL,
                    ResultMetadataType.POSSIBLE_COUNTRY);

    private CameraManager mCameraManager;
    private CaptureActivityHandler mHandler;
    private Result mSavedResultToShow;
    private ViewfinderView mViewfinderView;
    private boolean mHasSurface;
    private Map<DecodeHintType, ?> mDecodeHints;
    private InactivityTimer mInactivityTimer;
    private BeepManager mBeepManager;

    private Timer mTimer;

    public ViewfinderView getViewfinderView() {
        return mViewfinderView;
    }

    public Handler getHandler() {
        return mHandler;
    }

    CameraManager getCameraManager() {
        return mCameraManager;
    }

    @Override
    public void onCreate(Bundle icicle) {
        super.onCreate(icicle);

        final Context ctx = this;
        final Activity activity = this;
        final CaptureActivity that = this;

        CardScrollView csr = new CardScrollView(ctx);
        csr.setAdapter(new CardScrollAdapter() {
            @Override
            public int getCount() {
                return 1;
            }

            @Override
            public Object getItem(int i) {
                return null;
            }

            @Override
            public View getView(int i, View view, ViewGroup viewGroup) {
                View convertView;
                LayoutInflater inflater = (LayoutInflater) getApplicationContext().getSystemService(Context.LAYOUT_INFLATER_SERVICE);
                convertView = inflater.inflate(R.layout.activity_capture, viewGroup);

                mHasSurface = false;
                mInactivityTimer = new InactivityTimer(activity);
                mBeepManager = new BeepManager(activity);

                mViewfinderView = (ViewfinderView) convertView.findViewById(R.id.viewfinder_view);

                // CameraManager must be initialized here, not in onCreate(). This is necessary because we don't
                // want to open the camera driver and measure the screen size if we're going to show the help on
                // first launch. That led to bugs where the scanning rectangle was the wrong size and partially
                // off screen.
                mCameraManager = new CameraManager(getApplication());
                mViewfinderView.setCameraManager(mCameraManager);

                mHandler = null;

                SurfaceView surfaceView = (SurfaceView) convertView.findViewById(R.id.preview_view);
                SurfaceHolder surfaceHolder = surfaceView.getHolder();

                if (mHasSurface) {
                    // The activity was paused but not stopped, so the surface still exists. Therefore
                    // surfaceCreated() won't be called, so init the camera here.
                    initCamera(surfaceHolder);
                } else {
                    // Install the callback and wait for surfaceCreated() to init the camera.
                    surfaceHolder.addCallback(that);
                }

                mBeepManager.updatePrefs();

                mInactivityTimer.onResume();

                return convertView;
            }

            @Override
            public int getPosition(Object o) {
                return 1;
            }
        });
        csr.activate();
        setContentView(csr);
    }

    @Override
    protected void onResume() {
        super.onResume();

        final Context ctx = this;
        final Activity activity = this;

        mTimer = new Timer();
        mTimer.schedule(new TimerTask() {
            @Override
            public void run() {
                activity.runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        Intent intent = new Intent(ctx, MainActivity.class);
                        intent.putExtra("qr_type", "-1");
                        intent.putExtra("qr_data", "");
                        startActivityForResult(intent, 2);
                    }
                });
            }
        }, 15000);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == 2) {
            if (resultCode == RESULT_OK)
                finish();
        }
    }

    @Override
    protected void onPause() {
        mTimer.cancel();

        if (mHandler != null) {
            mHandler.quitSynchronously();
            mHandler = null;
        }
        mInactivityTimer.onPause();
        mCameraManager.closeDriver();
        if (!mHasSurface) {
            SurfaceView surfaceView = (SurfaceView) findViewById(R.id.preview_view);
            SurfaceHolder surfaceHolder = surfaceView.getHolder();
            surfaceHolder.removeCallback(this);
        }
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        mInactivityTimer.shutdown();
        super.onDestroy();
    }

    private void decodeOrStoreSavedBitmap(Bitmap bitmap, Result result) {
        // Bitmap isn't used yet -- will be used soon
        if (mHandler == null) {
            mSavedResultToShow = result;
        } else {
            if (result != null) {
                mSavedResultToShow = result;
            }
            if (mSavedResultToShow != null) {
                Message message = Message.obtain(mHandler,
                        1, mSavedResultToShow);
                mHandler.sendMessage(message);
            }
            mSavedResultToShow = null;
        }
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        if (holder == null) {
            Log.e(TAG,
                    "*** WARNING *** surfaceCreated() gave us a null surface!");
        }
        if (!mHasSurface) {
            mHasSurface = true;
            initCamera(holder);
        }
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        mHasSurface = false;
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width,
            int height) {

    }

    /**
     * A valid barcode has been found, so give an indication of success and show
     * the results.
     *
     * @param rawResult
     *            The contents of the barcode.
     * @param scaleFactor
     *            amount by which thumbnail was scaled
     * @param barcode
     *            A greyscale bitmap of the camera data which was decoded.
     */
    public void handleDecode(Result rawResult, Bitmap barcode, float scaleFactor) {
        mInactivityTimer.onActivity();

        boolean fromLiveScan = barcode != null;
        if (fromLiveScan) {
            mBeepManager.playBeepSoundAndVibrate();
        }

        handleDecodeInternally(rawResult, barcode);
    }

    // Put up our own UI for how to handle the decoded contents.
    private void handleDecodeInternally(Result rawResult, Bitmap barcode) {
        mTimer.cancel();

        ParsedResult parsedResult = ResultParser.parseResult(rawResult);

        Intent intent = new Intent(this, MainActivity.class);
        intent.putExtra("qr_type", parsedResult.getType().toString());
        intent.putExtra("qr_data", parsedResult.toString());
        startActivityForResult(intent, 2);
    }

    private void initCamera(SurfaceHolder surfaceHolder) {
        if (surfaceHolder == null) {
            throw new IllegalStateException("No SurfaceHolder provided");
        }
        if (mCameraManager.isOpen()) {
            Log.w(TAG,
                    "initCamera() while already open -- late SurfaceView callback?");
            return;
        }
        try {
            mCameraManager.openDriver(surfaceHolder);
            // Creating the handler starts the preview, which can also throw a RuntimeException.
            if (mHandler == null) {
                mHandler = new CaptureActivityHandler(this, null, mDecodeHints,
                        null, mCameraManager);
            }

            decodeOrStoreSavedBitmap(null, null);
        } catch (IOException e) {
            Log.w(TAG, e);
            displayFrameworkBugMessageAndExit();
        } catch (InterruptedException e) {
            Log.w(TAG, e);
            displayFrameworkBugMessageAndExit();
        }
    }

    private void displayFrameworkBugMessageAndExit() {
        Intent intent = new Intent(this, MainActivity.class);
        intent.putExtra("qr_type", "-2");
        intent.putExtra("qr_data", "");
        startActivityForResult(intent, 2);
    }

    public void drawViewfinder() {
        mViewfinderView.drawViewfinder();
    }
}

