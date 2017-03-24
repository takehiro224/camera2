package com.example.tkwatanabe.recodedata.camera;

import android.content.Context;
import android.graphics.ImageFormat;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.util.Size;

import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Created by tkwatanabe on 2017/03/23.
 * カメラ選択のクラス
 * 「カメラデバイス」「プレビュー/出力画像ファイルサイズ」を指定する
 *
 * 「TextureViewのサイズ」「カメラデバイスが出力可能な画像サイズ」を比較
 *  プレビューは拡大すれば良いのでTextureViewのサイズの半分以上の選択肢の中から、最小のものを選択する
 */

public class CameraChooser {

    private Context context;

    private int width;
    private int height;

    public CameraChooser(Context context, int width, int height) {
        this.context = context;
        this.width = width;
        this.height = height;
    }

    /**
     *
     * @return
     */
    public CameraInfo chooseCamera() {
        CameraManager cameraManager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);

        try {
            String[] cameraIds = cameraManager.getCameraIdList();

            for(String cameraId : cameraIds) {
                //カメラデバイスの特徴を取得する
                CameraCharacteristics cameraCharacteristics = cameraManager.getCameraCharacteristics(cameraId);
                if(!isBackFacing(cameraCharacteristics)) continue;

                //設定可能な画像サイズやプレビューサイズを取得する
                StreamConfigurationMap map;
                if((map = cameraCharacteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)) == null) {
                    //ストリーム情報を取得できないから採用しない
                    continue;
                }

                Size pictureSize;
                if((pictureSize = chooseImageSize(map)) == null) {
                    //適切なサイズがないから採用しない
                    continue;
                }

                Size previewSize;
                if((previewSize = choosePreviewSize(map)) == null) {
                    //適切なサイズがないから採用しない
                    continue;
                }

                //カメラセンサーのついている向き
                //通常は90度だが、Nexus5Xなど一部の端末は270度になっている
                Integer sensorOrientation;
                if((sensorOrientation = cameraCharacteristics.get(CameraCharacteristics.SENSOR_ORIENTATION)) == null) {
                    //取得できないから採用しない
                    continue;
                }

                //ここまで合格ならそれを返す
                CameraInfo cameraInfo = new CameraInfo();
                cameraInfo.setCameraId(cameraId);
                cameraInfo.setPictureSize(pictureSize);
                cameraInfo.setPreviewSize(previewSize);
                cameraInfo.setSensorOrientation(sensorOrientation);

                return cameraInfo;
            }
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
        return null;
    }

    private Size chooseImageSize(StreamConfigurationMap map) {
        //設定可能な写真サイズのリスト
        Size[] pictureSizes = map.getOutputSizes(ImageFormat.JPEG);
        //プレビュー用のTextureViewより大きい中で最小サイズを選択する
        return getMinimalSize(width / 2, height / 2, pictureSizes);
    }

    private Size choosePreviewSize (StreamConfigurationMap map) {
        //設定可能なプレビューサイズのリスト
        Size[] previewSizes = map.getOutputSizes(SurfaceTexture.class);
        //プレビュー用のTextureViewの半分より大きい中で最小サイズを選択する
        return getMinimalSize(width / 2, height / 2, previewSizes);
    }

    private boolean isBackFacing(CameraCharacteristics cameraCharacteristics) {
        Integer facing = cameraCharacteristics.get(CameraCharacteristics.LENS_FACING);
        //カメラの向きが取得できない or 前面カメラの場合は採用しない
        return (facing != null && facing == CameraCharacteristics.LENS_FACING_BACK);
    }

    private Size getMinimalSize(int minWidth, int minHeight, Size[] sizes) {
        List<Size> sizeList = Arrays.asList(sizes);

        //面積の小さい順に並べる
        Collections.sort(sizeList, new Comparator<Size>() {
            @Override
            public int compare(Size o1, Size o2) {
                return o1.getWidth() * o1.getHeight() - o2.getWidth() * o2.getHeight();
            }
        });

        for(Size size : sizeList) {
            if((size.getWidth() >= minWidth && size.getHeight() >= minHeight) || (size.getWidth() >= minHeight && size.getHeight() >= minWidth)) {
                return size;
            }
        }
        return null;
    }
}
