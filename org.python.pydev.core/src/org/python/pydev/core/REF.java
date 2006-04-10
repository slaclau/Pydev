/*
 * Created on Nov 12, 2004
 *
 * @author Fabio Zadrozny
 */
package org.python.pydev.core;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.OutputStream;
import java.io.Reader;
import java.io.StringReader;
import java.io.UnsupportedEncodingException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.Charset;
import java.util.HashSet;
import java.util.Set;

import org.apache.commons.codec.binary.Base64;
import org.eclipse.core.filebuffers.FileBuffers;
import org.eclipse.core.filebuffers.ITextFileBuffer;
import org.eclipse.core.filebuffers.ITextFileBufferManager;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.Path;
import org.eclipse.jface.text.Document;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.util.Assert;
import org.python.pydev.core.log.Log;


/**
 * @author Fabio Zadrozny
 */
public class REF {

    public static boolean hasAttr(Object o, String attr){
        try {
            o.getClass().getDeclaredField(attr);
        } catch (SecurityException e) {
            return false;
        } catch (NoSuchFieldException e) {
            return false;
        }
        return true;
    }

    public static Field getAttr(Object o, String attr){
        try {
            return o.getClass().getDeclaredField(attr);
        } catch (SecurityException e) {
            return null;
        } catch (NoSuchFieldException e) {
            return null;
        }
    }
    
    public static Object getAttrObj(Object o, String attr){
        if (REF.hasAttr(o, attr)) {
            Field field = REF.getAttr(o, attr);
            try {
                Object obj = field.get(o);
                return obj;
            }catch (Exception e) {
                return null;
            }
        }
        return null;
    }

    /**
     * @param file the file we want to read
     * @return the contents of the fil as a string
     */
    public static String getFileContents(File file) {
        try {
            FileInputStream stream = new FileInputStream(file);
            return getFileContents(stream, null, null);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * @param stream
     * @return
     * @throws IOException
     */
    private static String getFileContents(InputStream contentStream, String encoding, IProgressMonitor monitor) throws IOException {
        Reader in= null;
        final int DEFAULT_FILE_SIZE= 15 * 1024;

        if (encoding == null){
            in= new BufferedReader(new InputStreamReader(contentStream), DEFAULT_FILE_SIZE);
        }else{
            try {
                in = new BufferedReader(new InputStreamReader(contentStream, encoding), DEFAULT_FILE_SIZE);
            } catch (UnsupportedEncodingException e) {
                Log.log(e);
                //keep going without the encoding
                in= new BufferedReader(new InputStreamReader(contentStream), DEFAULT_FILE_SIZE);
            }
        }
        
        StringBuffer buffer= new StringBuffer(DEFAULT_FILE_SIZE);
        char[] readBuffer= new char[2048];
        int n= in.read(readBuffer);
        while (n > 0) {
            if (monitor != null && monitor.isCanceled())
                return null;

            buffer.append(readBuffer, 0, n);
            n= in.read(readBuffer);
        }
        return buffer.toString();
    }
    /**
     * @param o the object we want as a string
     * @return the string representing the object as base64
     */
    public static String getObjAsStr(Object o) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            ObjectOutputStream stream = new ObjectOutputStream(out);
            stream.writeObject(o);
            stream.close();
        } catch (Exception e) {
            Log.log(e);
            throw new RuntimeException(e);
        }
    
        return new String(encodeBase64(out));
    }

    public static byte[] encodeBase64(ByteArrayOutputStream out) {
        byte[] byteArray = out.toByteArray();
        return encodeBase64(byteArray);
    }

    public static byte[] encodeBase64(byte[] byteArray) {
        return Base64.encodeBase64(byteArray);
    }
    
    /**
     * 
     * @param persisted the base64 string that should be converted to an object.
     * @param readFromFileMethod should be the calback from the plugin that is calling this function
     * 
     * 
     * The callback should be something as:

        new ICallback<Object, ObjectInputStream>(){

            public Object call(ObjectInputStream arg) {
                try {
                    return arg.readObject();
                } catch (IOException e) {
                    throw new RuntimeException(e);
                } catch (ClassNotFoundException e) {
                    throw new RuntimeException(e);
                }
            }};

     * 
     * @return
     * @throws IOException
     * @throws ClassNotFoundException
     */
    public static Object getStrAsObj(String persisted, ICallback<Object, ObjectInputStream> readFromFileMethod) throws IOException, ClassNotFoundException {
        InputStream input = new ByteArrayInputStream(decodeBase64(persisted));
        Object o = readFromInputStreamAndCloseIt(readFromFileMethod, input);
        return o;
    }

    /**
     * @param readFromFileMethod
     * @param input
     * @return
     * @throws IOException
     */
    public static Object readFromInputStreamAndCloseIt(ICallback<Object, ObjectInputStream> readFromFileMethod, InputStream input) {
        ObjectInputStream in = null;
        Object o = null;
        try {
            try {
                in = new ObjectInputStream(input);
                o = readFromFileMethod.call(in);
            } finally {
                if(in!=null){
                    in.close();
                }
                input.close();
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return o;
    }

    public static byte[] decodeBase64(String persisted) {
        return Base64.decodeBase64(persisted.getBytes());
    }

    public static void writeStrToFile(String str, String file) {
        writeStrToFile(str, new File(file));
    }
    
    public static void writeStrToFile(String str, File file) {
        try {
            FileOutputStream stream = new FileOutputStream(file);
            try {
                stream.write(str.getBytes());
            } finally{
                stream.close();
            }
        } catch (FileNotFoundException e) {
            Log.log(e);
        } catch (IOException e) {
            Log.log(e);
        }
    }
    

    /**
     * @param file
     * @param astManager
     */
    public static void writeToFile(Object o, File file) {
        try {
            OutputStream out = new FileOutputStream(file);
            writeToStreamAndCloseIt(o, out);
        } catch (Exception e) {
            Log.log(e);
        }
    }

    /**
     * @param o
     * @param out
     * @throws IOException
     */
    public static void writeToStreamAndCloseIt(Object o, OutputStream out) throws IOException {
        try {
            ObjectOutputStream stream = new ObjectOutputStream(out);
            stream.writeObject(o);
            stream.close();
        } catch (Exception e) {
            Log.log(e);
            throw new RuntimeException(e);
        } finally{
            out.close();
        }
    }
    
    public static Object readFromFile(File file){
    	try {
    		InputStream in = new FileInputStream(file);
    		try {
				ObjectInputStream stream = new ObjectInputStream(in);
				try {
					Object o = stream.readObject();
					return o;
				} finally {
					stream.close();
				}
			} finally {
				in.close();
			}
    	} catch (Exception e) {
    		Log.log(e);
    		return null;
    	}
    	
    }


    public static String getFileAbsolutePath(String f) {
        return getFileAbsolutePath(new File(f));
    }

    /**
     * @param f
     * @return
     */
    public static String getFileAbsolutePath(File f) {
        try {
            return f.getCanonicalPath();
        } catch (IOException e) {
            return f.getAbsolutePath();
        }
    }

    /**
     * Calls a method for an object
     * 
     * @param obj the object with the method we want to call
     * @param name the method name
     * @param args the arguments received for the call
     * @return the return of the method
     */
    public static Object invoke(Object obj, String name, Object... args) {
        //the args are not checked for the class because if a subclass is passed, the method is not correctly gotten
        //another method might do it...
        Method m = findMethod(obj, name, args);
        return invoke(obj, m, args);
    }

    
    public static Object invoke(Object obj, Method m, Object... args) {
        try {
            return m.invoke(obj, args);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
    
    public static Method findMethod(Object obj, String name, Object... args) {
        return findMethod(obj.getClass(), name, args);   
    }
    
    public static Method findMethod(Class class_, String name, Object... args) {
        try {
            Method[] methods = class_.getMethods();
            for (Method method : methods) {

                Class[] parameterTypes = method.getParameterTypes();
                if(method.getName().equals(name) && parameterTypes.length == args.length){
                    //check the parameters
                    int i = 0;
                    for (Class param : parameterTypes) {
                        if(!param.isInstance(args[i])){
                            continue;
                        }
                        i++;
                    }
                    //invoke it
                    return method;
                }
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        throw new RuntimeException("The method with name: "+name+" was not found (or maybe it was found but the parameters didn't match).");
    }

    public static char [] INVALID_FILESYSTEM_CHARS = {
        '!', '@', '#', '$', '%', '^', '&', '*', 
        '(', ')', '[', ']', '{', '}', '=', '+',
        '.', ' ', '`', '~', '\'', '"', ',', ';'};

    public static String getValidProjectName(IProject project) {
        String name = project.getName();
        
        for (char c : INVALID_FILESYSTEM_CHARS) {
            name = name.replace(c, '_');
        }
        
        return name;
    }

    /**
     * Makes an equal comparisson taking into account that one of the parameters may be null.
     */
    public static boolean nullEq(Object o1, Object o2){
        if (o1 == null && o2 == null){
            return true;
        }
        if(o1 == null || o2 == null){
            return false;
        }
        return o1.equals(o2);
    }

    /**
     * @return the document given its 'filesystem' file
     */
    public static IDocument getDocFromFile(java.io.File f) {
        IPath path = Path.fromOSString(getFileAbsolutePath(f));
        IDocument doc = getDocFromPath(path);
        if (doc == null) {
            return getPythonDocFromFile(f);
        }
        return doc;
    }

    /**
     * @return the document given its 'filesystem' file (checks for the declared python encoding in the file)
     */
    private static IDocument getPythonDocFromFile(java.io.File f) {
        try {
            Assert.isTrue(f.exists(), "The file: "+f+" does not exist.");
            Assert.isTrue(f.isFile(), "The file: "+f+" is not recognized as a file.");
            
            FileInputStream stream = new FileInputStream(f);
            String fileContents = "";
            try {
                String encoding = getPythonFileEncoding(f);
                fileContents = getFileContents(stream, encoding, null);
            } finally {
                stream.close();
            }
            return new Document(fileContents);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * @return null if it was unable to get the document from the path (this may happen if it was not refreshed).
     * Or the document that represents the file
     */
    public static IDocument getDocFromPath(IPath path) {
        ITextFileBufferManager textFileBufferManager = FileBuffers.getTextFileBufferManager();
        
        if(textFileBufferManager != null){//we don't have it in tests
            ITextFileBuffer textFileBuffer = textFileBufferManager.getTextFileBuffer(path);

            if(textFileBuffer != null){ //we don't have it when it is not properly refreshed
                return textFileBuffer.getDocument();
            }
        }
        return null;
    }

    /**
     * Returns a document, created with the contents of a resource (first tries to get from the 'FileBuffers',
     * and if that fails, it creates one reading the file.
     */
    public static IDocument getDocFromResource(IResource resource) {
        IProject project = resource.getProject();
        if (project != null && resource instanceof IFile) {
    
            IFile file = (IFile) resource;
    
            try {
                if(! file.isSynchronized(IResource.DEPTH_ZERO)){
                    file.refreshLocal(IResource.DEPTH_ZERO, new NullProgressMonitor());
                }
                IPath path = file.getFullPath();
    
                IDocument doc = getDocFromPath(path);
                if(doc == null){
                    //can this actually happen?... yeap, it can
                    InputStream contents = file.getContents();
                    int i = contents.available();
                    byte b[] = new byte[i];
                    contents.read(b);
                    doc = new Document(new String(b));
                }
                return doc;
            } catch (Exception e) {
                Log.log(e);
            }
        }
        return null;
    }
    
    

    /**
     * The encoding declared in the document is returned (according to the PEP: http://www.python.org/doc/peps/pep-0263/)
     */
    public static String getPythonFileEncoding(IDocument doc) {
        Reader inputStreamReader = new StringReader(doc.get());
        return getPythonFileEncoding(inputStreamReader);
    }
    
    /**
     * The encoding declared in the file is returned (according to the PEP: http://www.python.org/doc/peps/pep-0263/)
     */
    public static String getPythonFileEncoding(File f) {
        try {
            Reader inputStreamReader = new InputStreamReader(new FileInputStream(f));
            return getPythonFileEncoding(inputStreamReader);
        } catch (FileNotFoundException e) {
            return null;
        }
    }

    /**
     * The encoding declared in the reader is returned (according to the PEP: http://www.python.org/doc/peps/pep-0263/)
     * -- may return null
     */
    private static String getPythonFileEncoding(Reader inputStreamReader) {
        String ret = null;
        BufferedReader reader = new BufferedReader(inputStreamReader);
        try{
            //pep defines that coding must be at 1st or second line: http://www.python.org/doc/peps/pep-0263/
            String l1 = reader.readLine();
            String l2 = reader.readLine();
            
            String lEnc = null;
            //encoding must be specified in first or second line...
            if (l1 != null && l1.toLowerCase().indexOf("coding") != -1){
                lEnc = l1; 
            }
            else if (l2 != null && l2.toLowerCase().indexOf("coding") != -1){
                lEnc = l2; 
            }
            else{
                ret = null;
            }
            
            if(lEnc != null){
                lEnc = lEnc.trim();
                if(lEnc.length() == 0){
                    ret = null;
                    
                }else if(lEnc.charAt(0) == '#'){ //it must be a comment line
                    
                    //ok, the encoding line is in lEnc
                    lEnc = lEnc.substring(lEnc.indexOf("coding")+6);
                    
                    char c;
                    while(lEnc.length() > 0 && ((c = lEnc.charAt(0)) == ' ' || c == ':' || c == '=')) {
                        lEnc = lEnc.substring(1);
                    }
    
                    StringBuffer buffer = new StringBuffer();
                    while(lEnc.length() > 0 && ((c = lEnc.charAt(0)) != ' ' || c == '-' || c == '*')) {
                        
                        buffer.append(c);
                        lEnc = lEnc.substring(1);
                    }
    
                    ret = buffer.toString().trim();
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }finally{
            try {reader.close();} catch (IOException e1) {}
        }
        ret = getValidEncoding(ret);
        return ret;
    }

    public static Set<String> encodings = new HashSet<String>();
    public static String getValidEncoding(String ret) {
        if(ret == null){
            return ret;
        }
        final String lower = ret.trim().toLowerCase();
        if(lower.startsWith("latin")){
            if(lower.indexOf("1") != -1){
                return "latin1"; //latin1
            }
        }
        if(lower.startsWith("utf")){
            if(lower.endsWith("8")){
                return "UTF-8"; //exact match
            }
        }
        if(!Charset.isSupported(ret)){
            String msg = "The encoding found: "+ret+" is not a valid encoding.";
            Log.log(IStatus.ERROR, msg, new UnsupportedEncodingException(msg));
            return null; //ok, we've been unable to make it supported (better return null than an unsupported encoding).
        }
        return ret;
    }

}

