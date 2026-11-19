package dev.zakocoder.app.device;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;

/** Private, UID checked IPC. The client token owns the entire display lifetime. */
interface IVirtualScreen {
    String create(int width, int height, int densityDpi, IBinder client);
    String launch(String packageName);
    String execute(String packageName, String arguments);
    ParcelFileDescriptor capture();
    oneway void cancel();
    void close();
}
