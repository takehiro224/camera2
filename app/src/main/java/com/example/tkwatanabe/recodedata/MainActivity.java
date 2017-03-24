package com.example.tkwatanabe.recodedata;

import android.app.Fragment;
import android.content.Intent;
import android.net.Uri;
import android.provider.MediaStore;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;

import com.example.tkwatanabe.recodedata.camera.CameraFragment;

import java.io.File;

public class MainActivity extends AppCompatActivity {

    Uri mPictureUri;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        Fragment cameraFragment = new CameraFragment();
        getFragmentManager()
                .beginTransaction()
                .replace(android.R.id.content, cameraFragment)
                .addToBackStack(null)
                .commit();
    }
}
