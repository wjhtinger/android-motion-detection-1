package com.jjoe64.motiondetection.motiondetection;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.ImageFormat;
import android.graphics.SurfaceTexture;
import android.hardware.Camera;
import android.media.MediaRecorder;
import android.net.Uri;
import android.opengl.GLES11Ext;
import android.os.Environment;
import android.os.Handler;
import android.util.Log;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.widget.TextView;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static java.lang.Thread.sleep;

public class MotionDetector {
    class MotionDetectorThread extends Thread {
        private AtomicBoolean isRunning = new AtomicBoolean(true);

        public void stopDetection() {
            isRunning.set(false);
        }

        @Override
        public void run() {
            while (isRunning.get()) {
                long now = System.currentTimeMillis();
                if (now-lastCheck > checkInterval) {
                    lastCheck = now;

                    //刚启动摄像头图像不稳，可能误判，因此忽略前面几帧
                    if(detectCount < 5){
                        detectCount++;
                        mCamera.addCallbackBuffer(mBuffer);
                        continue;
                    }


                    if(safeToTakePicture == false){
                        continue;
                    }

                    if (nextData.get() != null) {
                        int[] img = ImageProcessing.decodeYUV420SPtoLuma(nextData.get(), nextWidth.get(), nextHeight.get());

                        // check if it is too dark
                        int lumaSum = 0;
                        for (int i : img) {
                            lumaSum += i;
                        }
                        if (lumaSum < minLuma) {
                            if (motionDetectorCallback != null) {
                                mHandler.post(new Runnable() {
                                    @Override
                                    public void run() {
                                        motionDetectorCallback.onTooDark();
                                    }
                                });
                            }
                        } else if (detector.detect(img, nextWidth.get(), nextHeight.get())) {
                            // check
                            if (motionDetectorCallback != null) {
                                mHandler.post(new Runnable() {
                                    @Override
                                    public void run() {
                                        motionDetectorCallback.onMotionDetected();
                                    }
                                });
                            }

                            if(safeToTakePicture == true){
                                if(contentType == 0){
                                    //takePicturefromJpg();
                                    takePicturefromRaw(nextData.get(), nextWidth.get(), nextHeight.get());
                                }else if(contentType == 1){
                                    takeVideo();
                                }
                            }

                        }

                        if(inPreview)
                            mCamera.addCallbackBuffer(mBuffer);
                    }
                }
                try {
                    Thread.sleep(10);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
        }
    }

    private final AggregateLumaMotionDetection detector;
    private long checkInterval = 200;
    private long lastCheck = 0;
    private MotionDetectorCallback motionDetectorCallback;
    private Handler mHandler = new Handler();

    private AtomicReference<byte[]> nextData = new AtomicReference<>();
    private AtomicInteger nextWidth = new AtomicInteger();
    private AtomicInteger nextHeight = new AtomicInteger();
    private int minLuma = 1000;
    private MotionDetectorThread worker;

    private Camera mCamera;
    private boolean inPreview;
    private SurfaceHolder previewHolder;
    private Context mContext;
    private SurfaceView mSurface;

    SurfaceTexture mSurfaceTexture;
    private int cameraId = Camera.CameraInfo.CAMERA_FACING_FRONT;
    private String dirString = null;
    private byte[] mBuffer;
    private boolean safeToTakePicture = true;
    private long detectCount;
    private int contentType = 0;
    private static MotionDetector mMotionDetector = null;


    public MotionDetector(Context context, SurfaceView previewSurface) {
        detector = new AggregateLumaMotionDetection();
        mContext = context;

        if(previewSurface == null){
            mSurfaceTexture = new SurfaceTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES);
            mSurface = null;
        }else{
            mSurface = previewSurface;
        }
    }

    public void setMotionDetectorCallback(MotionDetectorCallback motionDetectorCallback) {
        this.motionDetectorCallback = motionDetectorCallback;
    }

    public void consume(byte[] data, int width, int height) {
        nextData.set(data);
        nextWidth.set(width);
        nextHeight.set(height);
    }

    public void setCheckInterval(long checkInterval) {
        this.checkInterval = checkInterval;
    }

    public void setMinLuma(int minLuma) {
        this.minLuma = minLuma;
    }

    public void setLeniency(int l) {
        detector.setLeniency(l);
    }

    public void setCamera(int c){
        if( c == 0){
            cameraId = Camera.CameraInfo.CAMERA_FACING_FRONT;
        }
        else if(c == 1){
            cameraId = Camera.CameraInfo.CAMERA_FACING_BACK;
        }
    }

    public void setContentType(int type){
        contentType = type;
    }

    public void setDirString(String dir){
        dirString = dir;
    }

    public static MotionDetector getInstance()
    {
        if(mMotionDetector == null){
            //mMotionDetector = new MotionDetector(getApplicationContext(), null);
        }

        return mMotionDetector;
    }

    public void onResume() {
        if (checkCameraHardware()) {
            mCamera = getCameraInstance();

            worker = new MotionDetectorThread();
            worker.start();

            if(mSurface == null){
                try {
                    mCamera.setPreviewTexture(mSurfaceTexture);
                } catch (IOException e) {
                    e.printStackTrace();
                }

                Camera.Parameters parameters = mCamera.getParameters();
                parameters.setPreviewSize(1280, 720);
                int size2 = 1920 * 1080 * 3;
                //size2  = size2 * ImageFormat.getBitsPerPixel(parameters.getPreviewFormat()) / 8;
                mBuffer = new byte[size2]; // class variable
                mCamera.addCallbackBuffer(mBuffer);
                mCamera.setPreviewCallbackWithBuffer(previewCallback);

                mCamera.setParameters(parameters);
                mCamera.startPreview();
                inPreview = true;

            }else{
                // configure preview
                previewHolder = mSurface.getHolder();
                previewHolder.addCallback(surfaceCallback);
                previewHolder.setType(SurfaceHolder.SURFACE_TYPE_PUSH_BUFFERS);
            }

            detectCount = 0;
        }
    }

    public boolean checkCameraHardware() {
        if (mContext.getPackageManager().hasSystemFeature(PackageManager.FEATURE_CAMERA)){
            // this device has a camera
            return true;
        } else {
            // no camera on this device
            return false;
        }
    }

    private Camera getCameraInstance(){
        Camera c = null;

        try {
            if (Camera.getNumberOfCameras() >= 2) {
                //if you want to open front facing camera use this line
                c = Camera.open(cameraId);
            } else {
                c = Camera.open();
            }
        }
        catch (Exception e){
            // Camera is not available (in use or does not exist)
            //txtStatus.setText("Kamera nicht zur Benutzung freigegeben");
        }
        return c; // returns null if camera is unavailable
    }

    private Camera.PreviewCallback previewCallback = new Camera.PreviewCallback() {

        /**
         * {@inheritDoc}
         */
        @Override
        public void onPreviewFrame(byte[] data, Camera cam) {
            if (data == null) return;
            Camera.Size size = cam.getParameters().getPreviewSize();
            if (size == null) return;
            Log.d("MotionDetectorSSSSSSSSS", "Using width=" + size.width + " height=" + size.height);
            consume(data, size.width, size.height);
        }
    };


    private SurfaceHolder.Callback surfaceCallback = new SurfaceHolder.Callback() {

        /**
         * {@inheritDoc}
         */
        @Override
        public void surfaceCreated(SurfaceHolder holder) {
            try {
                mCamera.setPreviewDisplay(previewHolder);
                //mCamera.setPreviewCallback(previewCallback);
            } catch (Throwable t) {
                Log.e("MotionDetector", "Exception in setPreviewDisplay()", t);
            }
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
            Camera.Parameters parameters = mCamera.getParameters();
            Camera.Size size = getBestPreviewSize(width, height, parameters);
            if (size != null) {
                parameters.setPreviewSize(size.width, size.height);
                Log.d("MotionDetector", "Using width=" + size.width + " height=" + size.height);
            }

            parameters.setPreviewSize(1280, 720);
            //parameters.setPictureSize(1280, 720);
            //parameters.setFocusMode(Camera.Parameters.FOCUS_MODE_FIXED);

            int size2 = 1920 * 1080 * 3;
            //size2  = size2 * ImageFormat.getBitsPerPixel(parameters.getPreviewFormat()) / 8;
            mBuffer = new byte[size2]; // class variable
            mCamera.addCallbackBuffer(mBuffer);
            mCamera.setPreviewCallbackWithBuffer(previewCallback);

            mCamera.setParameters(parameters);
            mCamera.startPreview();
            inPreview = true;
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public void surfaceDestroyed(SurfaceHolder holder) {
            // Ignore
        }
    };

    private static Camera.Size getBestPreviewSize(int width, int height, Camera.Parameters parameters) {
        Camera.Size result = null;

        for (Camera.Size size : parameters.getSupportedPreviewSizes()) {
            if (size.width <= width && size.height <= height) {
                if (result == null) {
                    result = size;
                } else {
                    int resultArea = result.width * result.height;
                    int newArea = size.width * size.height;

                    if (newArea > resultArea) result = size;
                }
            }
        }

        return result;
    }

    public void onPause() {
        releaseCamera();
        if (previewHolder != null) previewHolder.removeCallback(surfaceCallback);
        if (worker != null) worker.stopDetection();
    }

    private void releaseCamera(){
        if (mCamera != null){
            mCamera.setPreviewCallback(null);
            if (inPreview) mCamera.stopPreview();
            inPreview = false;
            mCamera.release();        // release the camera for other applications
            mCamera = null;
        }
    }

    private class PhotoHandler implements Camera.PictureCallback {

        @Override
        public void onPictureTaken(byte[] data, android.hardware.Camera camera) {
            try {
                String fileSrting = getFileString() + ".jpg";
                File outFile = new File(fileSrting);
                FileOutputStream outStream = new FileOutputStream(outFile);
                outStream.write(data);
                outStream.flush();
                outStream.close();

                Log.d("MotionDetector", "onPictureTaken - wrote bytes: " + data.length + " to " + outFile.getAbsolutePath());

                mediaScanBc(fileSrting);

            } catch (FileNotFoundException e) {
                e.printStackTrace();
            } catch (IOException e) {
                e.printStackTrace();
            } finally {
            }

            mCamera.startPreview();

            new Handler().postDelayed(new Runnable(){
                public void run() {
                    safeToTakePicture = true;
                }
            }, 1000);
        }
    }

    private void takePicturefromRaw(byte[] yuv420sp, int width, int height) {
        safeToTakePicture = false;
        detector.clear();

        int[] rgb = ImageProcessing.decodeYUV420SPtoRGB(yuv420sp, width, height);
        Bitmap bitmap = Bitmap.createBitmap(rgb, nextWidth.get(), nextHeight.get(), Bitmap.Config.ARGB_8888);

        final String fileSrting = getFileString() + ".jpg";
        File outFile = new File(fileSrting);
        try {
            FileOutputStream fos = new FileOutputStream(outFile);
            bitmap.compress(Bitmap.CompressFormat.JPEG, 80, fos);
        } catch (FileNotFoundException e) {
            e.printStackTrace();
        }

        mediaScanBc(fileSrting);

        if (motionDetectorCallback != null) {
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    motionDetectorCallback.onContent(fileSrting);
                }
            });
        }

        try {
            Thread.sleep(2000);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }

        safeToTakePicture = true;
        detectCount = 2;    //防止之前残余帧引起的误判断
    }

    private void takeVideo(){
        safeToTakePicture = false;
        detector.clear();

        mCamera.unlock();

        MediaRecorder recorder = new MediaRecorder();
        recorder.setCamera(mCamera);
        recorder.setVideoSource(MediaRecorder.VideoSource.CAMERA);
        recorder.setAudioSource(MediaRecorder.AudioSource.MIC);

        // 3: Set parameters, Following code does the same as getting a CamcorderProfile (but customizable)
        recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
        // 3.1 Video Settings
        recorder.setVideoSize(1280, 720);
        recorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264);
        recorder.setVideoEncodingBitRate(500000);
        recorder.setVideoFrameRate(15);
        // 3.2 Audio Settings
        recorder.setAudioChannels(1);
        recorder.setAudioSamplingRate(44100);
        recorder.setAudioEncoder(MediaRecorder.AudioEncoder.DEFAULT);
        recorder.setAudioEncodingBitRate(16);

        // Step 4: Set output file
        String fileSrting = getFileString() + ".mp4";
        recorder.setOutputFile(fileSrting);

        try {
            recorder.prepare();
            recorder.start();
        } catch (IOException e) {
            e.printStackTrace();
        }

        try {
            Thread.sleep(5000);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }

        recorder.stop();
        recorder.release();

        mediaScanBc(fileSrting);

        safeToTakePicture = true;
        detectCount = 2;
    }

    private void takePicturefromJpg(){
        safeToTakePicture = false;
        detector.clear();
        mCamera.takePicture(null, null, new PhotoHandler());
    }

    private String getFileString(){
        if(dirString == null){
            File sdCard = Environment.getExternalStorageDirectory();
            dirString = sdCard.getAbsolutePath() + "/MotionDetector";
        }
        File dir = new File(dirString);
        if (!dir.exists()) {
            dir.mkdirs();
        }

        Date date = new Date(System.currentTimeMillis());
        SimpleDateFormat dateFormat = new SimpleDateFormat("'Motion'_yyyyMMdd_HHmmss");
        String fileName = dir + "/" + dateFormat.format(date);
        Log.d("MotionDetector", "###############:" + fileName);

        return fileName;
    }

    private void mediaScanBc(String fileString){
        Intent mediaScanIntent = new Intent( Intent.ACTION_MEDIA_SCANNER_SCAN_FILE);
        mediaScanIntent.setData(Uri.fromFile(new File(fileString)));
        mContext.sendBroadcast(mediaScanIntent);
    }
}
