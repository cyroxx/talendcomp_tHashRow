package de.cimt.talendcomp.checksum;

import java.nio.charset.Charset;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 *
 * @author dkoch
 */
public enum HashFunction {
        MD5, SHA1("SHA-1"), SHA256("SHA-256");
        public final String text;
        
        private HashFunction(){
            this("#notset#");
        }
        private HashFunction(String name){
            this.text=name;
        }
        
        public final MessageDigest getMessageDigest(){
            try {
                return MessageDigest.getInstance(name());
            } catch (NoSuchAlgorithmException e) {
                throw new RuntimeException("Digest Algorithm " + name()  + " could not be found in this environment.", e);
            }
        }
        
        public byte[] digest(String content){
            return getMessageDigest().digest(content.getBytes(Charset.forName("UTF-8")));            
        }
        
     public static HashFunction parse(String value){
        
        if(value==null)
            value="";
        
        value=value.trim();
        
        for(HashFunction f : HashFunction.values()){
            if( f.name().equalsIgnoreCase( value ) || f.text.equalsIgnoreCase( value ) )
                return f;
                    
        }
        
        return MD5;
    }
       
}
