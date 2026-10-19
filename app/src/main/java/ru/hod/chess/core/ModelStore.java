package ru.hod.chess.core;

import java.io.*;
import java.nio.file.*;
import java.security.*;

/** Verified atomic extraction of packaged model DATA, never executable code. */
public final class ModelStore {
    public interface Source {InputStream open()throws IOException;}
    private ModelStore(){}
    public static synchronized File ensure(File destination,String expected,Source source)throws IOException{
        if(expected==null||!expected.matches("[0-9a-f]{64}"))throw new IOException("Нет контрольной суммы модели Maia");
        if(destination.isFile()&&hash(destination).equals(expected))return destination;
        File parent=destination.getParentFile();if(!parent.isDirectory()&&!parent.mkdirs())throw new IOException("Не удалось создать каталог моделей");
        File pending=File.createTempFile("maia-", ".part",parent);
        try{
            MessageDigest digest=digest();long total=0;
            try(InputStream in=source.open();FileOutputStream out=new FileOutputStream(pending)){
                byte[] buffer=new byte[32768];int n;while((n=in.read(buffer))!=-1){total+=n;if(total>256L*1024*1024)throw new IOException("Модель Maia слишком большая");digest.update(buffer,0,n);out.write(buffer,0,n);}out.getFD().sync();
            }
            if(!hex(digest.digest()).equals(expected))throw new IOException("Модель Maia повреждена: контрольная сумма не совпала");
            Files.move(pending.toPath(),destination.toPath(),StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);return destination;
        }finally{Files.deleteIfExists(pending.toPath());}
    }
    public static String hash(File file)throws IOException{
        MessageDigest digest=digest();try(InputStream in=new FileInputStream(file)){byte[] buffer=new byte[32768];int n;while((n=in.read(buffer))!=-1)digest.update(buffer,0,n);}return hex(digest.digest());
    }
    private static MessageDigest digest(){try{return MessageDigest.getInstance("SHA-256");}catch(NoSuchAlgorithmException e){throw new AssertionError(e);}}
    private static String hex(byte[] bytes){StringBuilder result=new StringBuilder();for(byte b:bytes)result.append(String.format(java.util.Locale.ROOT,"%02x",b&255));return result.toString();}
}
