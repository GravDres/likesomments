package ai.onnxruntime;
public final class OrtEnvironment {
    public static OrtEnvironment getEnvironment(){return new OrtEnvironment();}
    public OrtSession createSession(String file,OrtSession.SessionOptions options)throws OrtException{return new OrtSession();}
}
