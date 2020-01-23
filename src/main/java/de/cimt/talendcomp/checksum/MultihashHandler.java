package de.cimt.talendcomp.checksum;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.log4j.BasicConfigurator;
import org.apache.log4j.Level;
import org.apache.log4j.Logger;

/**
 *
 * @author daniel.koch@cimt-ag.de
 */
public class MultihashHandler {

    private static final Logger LOG=Logger.getLogger(MultihashHandler.class);
    private static final Class DYNACLASS;
    
    static{
        BasicConfigurator.configure();
//        LOG.setLevel( Level.DEBUG );
        
        Class c=null;
        try {
            c=Class.forName("routines.system.Dynamic");
        } catch (ClassNotFoundException ex) {
            c=null;
        }
        DYNACLASS=c;
    }
    public static class ColumnHandler {

        public final String colname;
        public final boolean trim;
        public final CaseSensitive caseHandling;
        public PropertyAccessor accessor;
        
        private ColumnHandler(String newColname) {
            final int startpos = newColname.indexOf("[");
            boolean settrim = false;
            CaseSensitive setcase = CaseSensitive.NOT_IN_USE;
            if (startpos > 0) {
                final String handlingStr = newColname.substring(startpos).toUpperCase();
                newColname = newColname.substring(0, startpos);

                if (handlingStr.contains("C")) {
                    setcase = CaseSensitive.CASE_SENSITIVE;
                }
                if (handlingStr.contains("U")) {
                    setcase = CaseSensitive.UPPER_CASE;
                }
                if (handlingStr.contains("L")) {
                    setcase = CaseSensitive.LOWER_CASE;
                }
                if (handlingStr.contains("T")) {
                    settrim = true;
                }

            }
            this.colname = newColname;
            this.trim = settrim;
            this.caseHandling = setcase;

        }
        
        public NormalizeObjectConfig createNormalizeObjectConfig(){
            return new NormalizeObjectConfig( caseHandling, trim );
        }
        
        @Override
        public String toString() {
            return "["+ colname + "] trim=" + trim + ", caseHandling=" + caseHandling ;
        }

    }

    private class PropertyAccessor {

        Field publicField;
        final String name;
        public boolean dynamic=false;

        /**
         * Get the property type
         *
         * @return the property type
         */
        public Class<?> getPropertyType() {
//      if(readMethod != null)
//         return readMethod.getReturnType();
//      else
            return publicField.getType();
        }

        /**
         * Set the property value on a given object
         *
         * @param invokee the target object
         * @param value the new value
         */
        public void setPropertyValue(Object invokee, Object value) {
            if (publicField != null) {
                try {
                    publicField.set(invokee, value);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            } else {
                throw new UnsupportedOperationException("Property not writable " + name);
            }
        }

        /**
         * Fetch the property value from a given object
         *
         * @param invokee the object to query
         * @return the property value
         */
        public Object getPropertyValue(Object invokee) {
            try {
                return publicField.get(invokee);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

        /**
         * Create a new property accessor with a given name
         *
         * @param name the property name
         */
        public PropertyAccessor(String name, Field field) {
            this.name = name;
            this.publicField = field;
        }

        /**
         * Create a new property accessor with a given name
         *
         * @param name the property name
         */
        public PropertyAccessor(String name) {
            this.name = name;
            this.publicField = null;
        }

        /**
         * Returns the property name
         *
         * @return the name
         */
        public String getName() {
            return name;
        }
    }
  
    private class DynamicsPropertyAccessor extends PropertyAccessor{
        // accessor to field storing the dynamic in rowstruct
        final PropertyAccessor dynamicAccessor; 
        // index of dynamic column connected to this PropertyAccessor
        final int index;                        
        final Method readMethod;
        final Method writeMethod;
                
        DynamicsPropertyAccessor(String name, PropertyAccessor parent, int index, Method readMethod, Method writeMethod){
            super(name);
            dynamicAccessor=parent;
            this.index=index;
            this.readMethod=readMethod;
            this.writeMethod=writeMethod;
        }

        @Override
        public Object getPropertyValue(Object invokee) {
            try {
                return readMethod.invoke(
                        dynamicAccessor.getPropertyValue( invokee )
                        , new Object[]{index});
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
        @Override
        public void setPropertyValue(Object invokee, Object value) {
            try {
                writeMethod.invoke(
                        dynamicAccessor.getPropertyValue( invokee )
                        , new Object[]{index, value});
            } catch (Exception e) {
                throw new RuntimeException(e);
            } 
        }

    }
    
    private final Map<String, List<ColumnHandler>> configMapping;
    private Map<String, PropertyAccessor> outputCache=null;
    private final boolean casesensitive;
    private final NormalizeConfig normalizeConfig;
    private final boolean ignoreMissingColumns;
    
    public MultihashHandler( NormalizeConfig normalizeConfig, String config, boolean casesensitive, boolean ignoreMissingColumns ){
        this.casesensitive=casesensitive;
        this.normalizeConfig=normalizeConfig;
        this.ignoreMissingColumns=ignoreMissingColumns;
        
        configMapping = new HashMap<String, List<ColumnHandler>>();
        if(config==null)
            return;
        
    	final List<String> keys=Arrays.asList( config.split( "\\s*;\\s*") );
        
        for(int i=0, max=keys.size();i<max;i++ ){
            String entry = keys.get( i );
            String[] pair=entry.split("\\s*=\\s*");
            if(pair.length!=2)
                throw new java.lang.IllegalArgumentException("list of ; seperated entries containing key=value expected. found "+entry);
            
            List<ColumnHandler> fields=new ArrayList<ColumnHandler>();
            for(String colname : Arrays.asList( pair[1].split( "\\s*,\\s*"))){
                fields.add( new ColumnHandler(colname) );
            }
            configMapping.put( casesensitive ? pair[0] : pair[0].toUpperCase() , fields);
        }
    }
    
    private List<PropertyAccessor> exposeDynamicRows(Object row , PropertyAccessor dynamicAccessor){
        try {
            Object dynamic=dynamicAccessor.getPropertyValue(row);
//            final Class  clazz = dynamic.getClass();

            List<PropertyAccessor> pas=new ArrayList<PropertyAccessor>();
            
            //if( !clazz.getName().equals("routines.system.Dynamic") ){
            if( DYNACLASS==null && dynamicAccessor.dynamic ){
                LOG.debug("no dynamic found");
                return Collections.emptyList();
            }
            
            final int count= (int) DYNACLASS.getMethod("getColumnCount", new Class[]{}).invoke( dynamic, new Object[]{} );
            final Field metadatas = DYNACLASS.getField("metadatas");
            final Method setColumnValue = DYNACLASS.getMethod("setColumnValue", new Class[]{ int.class, Object.class });
            final Method getColumnValue = DYNACLASS.getMethod("getColumnValue", new Class[]{ int.class });
            final Method getMetadataByIndex=metadatas.get(dynamic).getClass().getMethod("get", new Class[]{ int.class });
            Method getRowname        =null;

            for(int i=0;i<count;i++){
                Object metadata=getMetadataByIndex.invoke(metadatas.get(dynamic), i);

                if(getRowname==null){
                    getRowname=metadata.getClass().getMethod("getName", new Class[]{});
                }
                pas.add( new DynamicsPropertyAccessor((String) getRowname.invoke(metadata, new Object[]{}), dynamicAccessor, i, getColumnValue, setColumnValue ) );
            }
            return pas;
        } catch (Throwable ex) {
            throw new RuntimeException(ex);
        }
        
    }

    private Map<String, PropertyAccessor> getAccessorMapping(Object row){
        final Class<?> clazz = row.getClass();
        List<PropertyAccessor> inputIntrospect = new ArrayList<PropertyAccessor>();

        for(Field f : clazz.getFields()) {
            LOG.debug( "field:" + f );
            if(Modifier.isPublic(f.getModifiers()) && !Modifier.isStatic(f.getModifiers())){
                inputIntrospect.add(new PropertyAccessor(f.getName(), f));
            }
        }

        Map<String, PropertyAccessor> propertyMapping = new HashMap<String, PropertyAccessor>();

        for(PropertyAccessor pa : inputIntrospect){
            String relatedProp= casesensitive ? pa.getName() : pa.getName().toUpperCase();
            Class colClazz=pa.getPropertyType();
            if(colClazz.equals(Object.class) && DYNACLASS!=null){
                try{
                    DYNACLASS.cast( pa.getPropertyValue(row) );
                    pa.dynamic=true;
                }catch(ClassCastException cce){}
            } else if( pa.getPropertyType().getName().equals("routines.system.Dynamic") ){
                pa.dynamic=true;
            }
            
            if(pa.dynamic){
                LOG.debug("Handle Dynamic "+relatedProp);
                List<PropertyAccessor> exposed=exposeDynamicRows( row, pa );
                for(PropertyAccessor dpa : exposed) {
                    relatedProp= casesensitive ? dpa.getName() : dpa.getName().toUpperCase();
                    LOG.debug("register dyn col "+relatedProp);
                    propertyMapping.put(relatedProp, dpa);
                }
            } else {
                    LOG.debug("register col "+relatedProp+ " type:"+pa.getPropertyType().getName());
                propertyMapping.put(relatedProp, pa);
            }
        }

        return propertyMapping;
        
    }
    
    /**
     * 
     * @param inputRow
     * @param outputRow
     * @param encoding
     * @param type
     * @return return a list of all hashes calculated as , seperated string
     */
    public String createHashes(Object inputRow, Object outputRow, HashOutputEncoding encoding, HashFunction type) {
        StringBuffer buf=new StringBuffer();
        
        if(outputCache==null){
            /**
             * first row the value is not set and we can inspect structure to build up
             * map of PropertyAccessor for reading and writing of values. 
             */
            Map<String, PropertyAccessor> propertyMapping = getAccessorMapping(inputRow);
            for(List<ColumnHandler> handlerList : configMapping.values()){
                for(ColumnHandler handler : handlerList ){
                    String colname = casesensitive ? handler.colname : handler.colname.toUpperCase();
                    handler.accessor=propertyMapping.get(colname);
                    if(handler.accessor==null && !ignoreMissingColumns)
                        throw new java.lang.reflect.MalformedParametersException(handler.colname + " is not accessible in flow. Please check Configuration");
                }
            }
            outputCache=getAccessorMapping(outputRow);
            
            if(!ignoreMissingColumns){
                for(String resultCol : configMapping.keySet()){
                    if(!outputCache.containsKey(resultCol)){
                        throw new java.lang.reflect.MalformedParametersException(resultCol + " is no part of the outgoing flow. Please check Configuration");
                    }
                }
            }
        }

        for (String targetCol : configMapping.keySet() ){            
            if(!outputCache.containsKey(targetCol)){
                continue;
            }
            Normalization norm=new Normalization(normalizeConfig);
            
            for(ColumnHandler col : configMapping.get(targetCol)){
                norm.add(  col.accessor.getPropertyValue(inputRow), col.createNormalizeObjectConfig() );
            }
            PropertyAccessor pa = outputCache.get(targetCol);
            pa.setPropertyValue(outputRow, norm.calculateHash(  type, encoding ) );
            
            if(buf.length()>0)
                buf.append(", ");
            
            buf.append( pa.name );
        }
        return buf.toString();
    }

    public Map<String, List<String>> mappingOfUsedIdentifiers() {
        Map<String, List<String>> res=new HashMap<String, List<String>>();
        for(Map.Entry<String, List<ColumnHandler>> entrySet : configMapping.entrySet()){
            List<String> idents=new ArrayList<String>();
            for(ColumnHandler value : entrySet.getValue()){
                idents.add(value.colname);
            }
            res.put( entrySet.getKey(), idents);
        }
        return res;
    }
     
}
