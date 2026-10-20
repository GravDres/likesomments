package ru.hod.chess;

import android.content.Context;
import java.io.File;
import java.security.KeyStore;

/** Remove only retired engine settings/data. Board history and overlay placement survive. */
final class LegacyUpgrade {
    static void run(Context context){
        android.content.SharedPreferences prefs=context.getSharedPreferences("hod",Context.MODE_PRIVATE);
        if(prefs.getBoolean("caissa23Migrated",false))return;
        context.getSharedPreferences("hod-api-key",Context.MODE_PRIVATE).edit().clear().apply();
        boolean keyRemoved=true;
        try{KeyStore keys=KeyStore.getInstance("AndroidKeyStore");keys.load(null);keys.deleteEntry("hod.tooken.api.v1");}
        catch(Exception ignored){keyRemoved=false;}
        File directory=new File(context.getFilesDir(),"maia");
        for(String name:new String[]{"maia3-5m.onnx","maia3-23m.onnx"}){
            File file=new File(directory,name);if(file.isFile())file.delete();
        }
        directory.delete();
        android.content.SharedPreferences.Editor edit=prefs.edit();
        for(String key:new String[]{"engineMode","maiaModel","maiaNetwork","maiaSampling","engineSkill","apiModel","apiBenchmarkSummary"})edit.remove(key);
        // A failed keystore cleanup can be retried on the next launch; never log key material.
        edit.putBoolean("caissa23Migrated",keyRemoved).apply();
    }
}
