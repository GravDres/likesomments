package ai.onnxruntime;
import java.nio.*;
public final class OnnxTensor implements AutoCloseable {
    final Object value;private OnnxTensor(Object value){this.value=value;}
    public static OnnxTensor createTensor(OrtEnvironment env,FloatBuffer data,long[] shape)throws OrtException{return new OnnxTensor(data);}
    public static OnnxTensor createTensor(OrtEnvironment env,LongBuffer data,long[] shape)throws OrtException{return new OnnxTensor(data);}
    public static OnnxTensor output(float[][] value){return new OnnxTensor(value);}
    public Object getValue()throws OrtException{return value;}
    public void close(){}
}
