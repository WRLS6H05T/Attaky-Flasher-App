# Keep the JS bridge entry points: they are invoked reflectively by the WebView.
-keepclassmembers class com.attaky.flasher.SerialBridge {
    public *;
}
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# usb-serial-for-android probes drivers by reflection.
-keep class com.hoho.android.usbserial.driver.** { *; }
