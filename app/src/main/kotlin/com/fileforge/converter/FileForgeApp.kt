package com.fileforge.converter

import android.app.Application
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader

class FileForgeApp : Application() {

    override fun onCreate() {
        super.onCreate()
        PDFBoxResourceLoader.init(applicationContext)
    }
}
