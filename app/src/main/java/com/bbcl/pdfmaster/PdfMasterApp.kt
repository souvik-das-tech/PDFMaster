package com.bbcl.pdfmaster

import android.app.Application
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader

class PdfMasterApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Fonts and encodings for the PDF library ship inside the app.
        PDFBoxResourceLoader.init(this)
        Store.init(this)
    }
}
