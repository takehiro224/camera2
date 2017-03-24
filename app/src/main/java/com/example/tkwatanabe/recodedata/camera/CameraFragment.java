package com.example.tkwatanabe.recodedata.camera;

import android.Manifest;
import android.app.Activity;
import android.app.Fragment;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.TotalCaptureResult;
import android.media.AudioManager;
import android.media.Image;
import android.media.ImageReader;
import android.media.MediaActionSound;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.support.annotation.NonNull;
import android.support.v4.app.ActivityCompat;
import android.support.v4.content.ContextCompat;
import android.util.Size;
import android.view.LayoutInflater;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import com.example.tkwatanabe.recodedata.R;

import java.nio.ByteBuffer;
import java.util.Arrays;

/**
 * Created by tkwatanabe on 2017/03/23.
 */

public class CameraFragment extends Fragment implements View.OnClickListener {

    //パーミッションのリクエストコード
    private static final int REQUEST_CAMERA_PERMISSION = 1;

    //テクスチャービュー
    private TextureView mTextureView;

    //写真撮影後、ファイルに保存したり、DBに保存するためのスレッド
    private HandlerThread mBackgroundThread;
    private Handler mBackgroundHandler;

    //写真撮影に使用するImageReader
    private ImageReader mImageReader;
    //カメラ情報(自作クラス)
    private CameraInfo mCameraInfo;
    //カメラデバイス
    private CameraDevice mCameraDevice;
    //セッション
    private CameraCaptureSession mCaptureSession;
    //撮影リクエストを作るためのビルダー
    private CaptureRequest.Builder mCaptureRequestBuilder;
    //撮影リクスト
    private CaptureRequest mCaptureRequest;
    //撮影音のためのMediaActionSound
    private MediaActionSound mSound;

    @Override //フラグメントが生成される時
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        //撮影音をロードする
        mSound = new MediaActionSound();
        mSound.load(MediaActionSound.SHUTTER_CLICK);
    }

    @Override //フラグメントのUIが生成される時
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_camera, container, false);
    }

    @Override
    public void onViewCreated(View view, Bundle savedInstanceState) {
        view.findViewById(R.id.ShutterButton).setOnClickListener(this);
        mTextureView = (TextureView) view.findViewById(R.id.PreviewTexture);
    }

    @Override
    public void onResume() {
        super.onResume();
        startCamera();
    }

    @Override
    public void onPause() {
        stopCamera();
        super.onPause();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        //MediaActionSoundを解放する
        mSound.release();
    }

    /**
     * カメラの起動処理
     */
    private void startCamera() {
        //画像処理を行うためのスレッドを立てる
        mBackgroundThread = new HandlerThread("CameraBackground");
        //スレッドを起動
        mBackgroundThread.start();
        //別スレッドのLooperを渡してHandlerを作成する => 別スレッド上で実行させることができる
        mBackgroundHandler = new Handler(mBackgroundThread.getLooper());

        if(mTextureView.isAvailable()) {
            //TextureViewの準備ができていればカメラを開く
            openCamera(mTextureView.getWidth(), mTextureView.getHeight());
        } else {
            //TextureViewの準備ができているのを待つ
            mTextureView.setSurfaceTextureListener(mTextureListner);
        }
    }

    //(1)TextureView作成
    private final TextureView.SurfaceTextureListener mTextureListner = new TextureView.SurfaceTextureListener() {

        @Override
        public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
            //カメラデバイスへの接続を開始する
            openCamera(width, height);
        }

        @Override
        public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {
            //プレビューを、新しいサイズに合わせて変形する
            transformTexture(width, height);
        }

        @Override
        public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
            return true;
        }

        @Override
        public void onSurfaceTextureUpdated(SurfaceTexture surface) {
        }
    };

    /**
     * カメラデバイスへのアクセスを行う
     * @param width
     * @param height
     */
    private void openCamera(int width, int height) {

        //パーミッション確認
        if (ContextCompat.checkSelfPermission(getActivity(), Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(getActivity(), new String[]{Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO}, 0);
            return;
        }

        //(2)(3)カメラデバイスを選択する
        mCameraInfo = new CameraChooser(getActivity(), width, height).chooseCamera();

        if(mCameraInfo == null) {
            //候補がない場合は何もしない
            return;
        }

        //画像処理を行うImageReaderを生成する
        mImageReader = ImageReader.newInstance(
          mCameraInfo.getPictureSize().getWidth(),
          mCameraInfo.getPictureSize().getHeight(),
          ImageFormat.JPEG, 2
        );
        //画像が得られる度に呼ばれるリスナーと、そのリスナーが動作するスレッドを設定する
        mImageReader.setOnImageAvailableListener(mOnImageAvailableListener, mBackgroundHandler);

        //TextureViewのサイズと端末の向きに合わせて、プレビューを変形させる
        transformTexture(width, height);

        //カメラを開く
        CameraManager manager = (CameraManager) getActivity().getSystemService(Context.CAMERA_SERVICE);

        try {
            //(4)カメラオープン
            //「@param callback」はカメラが無事に開かれ、プレビュー用のセッションを開始できるようになると呼ばれる
            manager.openCamera(mCameraInfo.getCameraId(), mStateCallback, mBackgroundHandler);
        } catch(CameraAccessException e) {
            e.printStackTrace();
        }
    }

    //画像が操作可能になったら呼ばれるリスナー
    private final ImageReader.OnImageAvailableListener mOnImageAvailableListener = new ImageReader.OnImageAvailableListener() {

        @Override
        public void onImageAvailable(ImageReader reader) {
            //ImageReaderからImageを取り出す
            Image image = reader.acquireLatestImage();
            //描画された画像のbyte列を取り出す
            ByteBuffer buffer = image.getPlanes()[0].getBuffer();
            byte[] data = new byte[buffer.remaining()];
            buffer.get(data);
            image.close();

            //画像ファイルとして保存する
            mBackgroundHandler.post(new PictureServer(getActivity(), data));
        }
    };


    //カメラ準備完了通知
    private final CameraDevice.StateCallback mStateCallback = new CameraDevice.StateCallback() {

        @Override
        public void onOpened(CameraDevice camera) {
            //カメラが開かれ、プレビューセッションが開始できる状態になった
            mCameraDevice = camera;
            createCameraPreviewSession();
        }

        @Override
        public void onDisconnected(CameraDevice camera) {
            camera.close();
            mCameraDevice = null;
        }

        @Override
        public void onError(CameraDevice camera, int error) {
            camera.close();
            mCameraDevice = null;
            Activity activity = getActivity();
            if(null != activity) {
                activity.finish();
            }
        }
    };

    /**
     * プレビューのセッションを作る
     */
    private void createCameraPreviewSession() {
        try {
            SurfaceTexture texture = mTextureView.getSurfaceTexture();

            // バッファサイズを、プレビューサイズに合わせる
            texture.setDefaultBufferSize(mCameraInfo.getPreviewSize().getWidth(), mCameraInfo.getPreviewSize().getHeight());

            // プレビューが描画されるSurface
            Surface surface = new Surface(texture);

            //(5)CaptureRequest作成
            mCaptureRequestBuilder = mCameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            mCaptureRequestBuilder.addTarget(surface);

            // (6)プレビュー用のセッション生成を要求する
            mCameraDevice.createCaptureSession(Arrays.asList(surface, mImageReader.getSurface()), mSessionStateCallback, null);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    private final CameraCaptureSession.StateCallback mSessionStateCallback = new CameraCaptureSession.StateCallback() {

        @Override
        public void onConfigured(@NonNull CameraCaptureSession cameraCaptureSession) {
            // カメラが閉じてしまっていた場合
            if (null == mCameraDevice) {
                return;
            }
            mCaptureSession = cameraCaptureSession;
            try {
                // オートフォーカスを設定する
                mCaptureRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
                // リクエスト作成
                mCaptureRequest = mCaptureRequestBuilder.build();
                //(7)RepeatSession作成 カメラプレビューを表示する
                mCaptureSession.setRepeatingRequest(mCaptureRequest, null, mBackgroundHandler);
            } catch (CameraAccessException e) {
                e.printStackTrace();
            }
        }

        @Override //設定失敗した
        public void onConfigureFailed(CameraCaptureSession session) {
        }
    };

    /**
     * TextureViewのサイズに合わせてプレビューに補正をかける
     * 画面の向きに合わせてプレビューを回転させる
     *
     * @param viewWidth
     * @param viewHeight
     */
    private void transformTexture(int viewWidth, int viewHeight) {
        Activity activity = getActivity();
        if(mTextureView == null || mCameraInfo == null || activity == null) {
            return;
        }

        int rotation = activity.getWindowManager().getDefaultDisplay().getRotation();

        Size viewSize = new Size(viewWidth, viewHeight);
        Matrix matrix = new Matrix();

        //TextureViewのサイズを示す矩形
        RectF viewRect = new RectF(0, 0, viewSize.getWidth(), viewSize.getHeight());

        //プレビュー領域を示す矩形
        RectF bufferRect = new RectF(0, 0, mCameraInfo.getPictureSize().getHeight(), mCameraInfo.getPictureSize().getWidth());

        float centerX = viewRect.centerX();
        float centerY = viewRect.centerY();

        if(rotation == Surface.ROTATION_90 || rotation == Surface.ROTATION_270) {
            /* 横の場合 */
            //中心を合わせる
            bufferRect.offset(centerX - bufferRect.centerX(), centerY = bufferRect.centerY());

            //TextureView用の矩形を、プレビュー用の矩形に合わせる
            matrix.setRectToRect(viewRect, bufferRect, Matrix.ScaleToFit.FILL);

            //拡大・縮小率を決定する
            float scale = Math.max(
                    (float) viewSize.getHeight() / mCameraInfo.getPictureSize().getHeight(),
                    (float) viewSize.getWidth() / mCameraInfo.getPictureSize().getWidth()
            );

            //拡大・縮小を行う
            matrix.postScale(scale, scale, centerX, centerY);

            //回転する
            matrix.postRotate(90 * (rotation - 2), centerX, centerY);

        } else if(rotation == Surface.ROTATION_180) {
            /* 上下逆の場合 */
            matrix.postRotate(180, centerX, centerY);
        }

        mTextureView.setTransform(matrix);
    }

    // カメラの停止処理を行う
    private void stopCamera() {
        // セッションを閉じる
        if (mCaptureSession != null) {
            mCaptureSession.close();
            mCaptureSession = null;
        }
        // カメラを閉じる
        if (mCameraDevice != null) {
            mCameraDevice.close();
            mCameraDevice = null;
        }
        // ImageReaderを閉じる
        if (mImageReader != null) {
            mImageReader.close();
            mImageReader = null;
        }
        // スレッドを止める
        mBackgroundThread.quitSafely();
        try {
            mBackgroundThread.join();
            mBackgroundThread = null;
            mBackgroundHandler = null;
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    @Override
    public void onClick(View v) {
        if (v.getId() == R.id.ShutterButton) {
            // 写真を撮影する
            takePicture();
        }
    }

    // 写真を撮影する
    private void takePicture() {
        try {
            // オートフォーカスをリクエストする
            mCaptureRequestBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_START);

            // オートフォーカスを実行し、終わったら撮影する
            mCaptureSession.capture(mCaptureRequestBuilder.build(),
                    new CameraCaptureSession.CaptureCallback() {
                        @Override
                        public void onCaptureCompleted(@NonNull CameraCaptureSession session,
                                                       @NonNull CaptureRequest request,
                                                       @NonNull TotalCaptureResult result) {
                            // 撮影する
                            captureStillPicture();
                        }
                    },
                    mBackgroundHandler); // 撮影結果の処理が行われるスレッド
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    // 写真撮影する
    private void captureStillPicture() {
        try {
            final Activity activity = getActivity();
            if (null == activity || null == mCameraDevice) {
                return;
            }
            // 撮影用のCaptureRequestを設定する
            final CaptureRequest.Builder captureBuilder = mCameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);

            // キャプチャ結果をImageReaderに渡す
            captureBuilder.addTarget(mImageReader.getSurface());

            // オートフォーカス
            captureBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);

            // 端末の回転角
            int rotation = activity.getWindowManager().getDefaultDisplay().getRotation();
            // カメラセンサーの方向
            int sensorOrientation = mCameraInfo.getSensorOrientation();
            // JPEG画像の方向を、カメラセンサーの方向と、端末の方向から計算する
            int jpegRotation = getPictureRotation(rotation, sensorOrientation);


            // JPEG画像の方向を設定する。
            captureBuilder.set(CaptureRequest.JPEG_ORIENTATION, jpegRotation);

            // 撮影が終わったら、フォーカスのロックを外すためのコールバック
            CameraCaptureSession.CaptureCallback captureCallback
                    = new CameraCaptureSession.CaptureCallback() {
                @Override
                public void onCaptureCompleted(@NonNull CameraCaptureSession session,
                                               @NonNull CaptureRequest request,
                                               @NonNull TotalCaptureResult result) {
                    unlockFocus();
                }
            };

            // 撮影音を鳴らす
            mSound.play(MediaActionSound.SHUTTER_CLICK);
            // カメラからデータの取得を止める
            mCaptureSession.stopRepeating();
            // 撮影する
            mCaptureSession.capture(captureBuilder.build(), captureCallback, null);

        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    /**
     *
     */
    private void unlockFocus() {
        try {
            // オートフォーカストリガーを外す
            mCaptureRequestBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER,
                    CameraMetadata.CONTROL_AF_TRIGGER_CANCEL);
            mCaptureSession.capture(mCaptureRequestBuilder.build(), null,
                    mBackgroundHandler);

            // プレビューに戻る
            mCaptureSession.setRepeatingRequest(mCaptureRequest, null,
                    mBackgroundHandler);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    /**
     *
     * @param deviceRotation
     * @param sensorOrientation
     * @return
     */
    public int getPictureRotation(int deviceRotation, int sensorOrientation) {

        switch (deviceRotation) {
            case Surface.ROTATION_0:
                return sensorOrientation;

            case Surface.ROTATION_90:
                return (sensorOrientation + 270) % 360;

            case Surface.ROTATION_180:
                return (sensorOrientation + 180) % 360;

            case Surface.ROTATION_270:
                return (sensorOrientation + 90) % 360;
        }

        return 0;
    }

    /**
     * 許可ダイアログの承認結果を受け取る。onCreate内で許可を確認し、結果の判断を行う
     */
    @Override
    public void onRequestPermissionsResult(int requestCode, String permissions[], int[] grantResults) {
        switch (requestCode) {
            case 0: {
                if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    //許可された場合
                } else {
                    //許可されなかった場合
                    Toast.makeText(getActivity(), "Failed to access the camera and microphone.\nclick allow when asked for permission.", Toast.LENGTH_LONG).show();
                }
                break;
            }
        }
    }
}
