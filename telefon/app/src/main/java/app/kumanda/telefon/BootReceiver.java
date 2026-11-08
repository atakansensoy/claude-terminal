package app.kumanda.telefon;
import android.content.BroadcastReceiver; import android.content.Context; import android.content.Intent;
/** Telefon acilinca arka plan baglantisini yeniden kur (adres kayitliysa). */
public class BootReceiver extends BroadcastReceiver {
  @Override public void onReceive(Context c, Intent i) {
    if (c.getSharedPreferences("kumanda", Context.MODE_PRIVATE).getString("url", null) != null) { try { NotifyService.start(c); } catch (Exception ignored) {} }
  }
}
