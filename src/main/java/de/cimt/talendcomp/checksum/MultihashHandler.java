package de.cimt.talendcomp.checksum;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.colllib.introspect.Introspector;
import org.colllib.introspect.PropertyAccessor;

/**
 *
 * @author daniel.koch@cimt-ag.de
 */
public class MultihashHandler {

    public static class ColumnHandler {

        public final String colname;
        public final boolean trim;
        public final CaseSensitive caseHandling;
        public PropertyAccessor accessor;
        
        private ColumnHandler(String newColname) {
            final int startpos = newColname.indexOf("[");
            boolean settrim = false;
            CaseSensitive setcase = CaseSensitive.NOT_IN_USE;
            System.err.println("newColname=" + newColname);
            if (startpos > 0) {
                final String handlingStr = newColname.substring(startpos).toUpperCase();
                newColname = newColname.substring(0, startpos);

                System.err.println("handle params " + handlingStr);
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

    private class DynamicsPropertyAccessor extends PropertyAccessor{
        // accessor to field storing the dynamic in rowstruct
        final PropertyAccessor dynamicAccessor; 
        // index of dynamic column connected to this PropertyAccessor
        final int index;                        
        
        DynamicsPropertyAccessor(String name, PropertyAccessor parent, int index){
            super(name);
            dynamicAccessor=parent;
            this.index=index;
        }

        public Object getPropertyValue(Object invokee) {
            try {
                
                return getReadMethod().invoke(
                        dynamicAccessor.getPropertyValue( invokee )
                        , new Object[]{index});
            } catch (IllegalArgumentException e) {
                throw new RuntimeException(e);
            } catch (IllegalAccessException e) {
                throw new RuntimeException(e);
            } catch (InvocationTargetException e) {
                throw new RuntimeException(e);
            }
        }
        @Override
        public void setPropertyValue(Object invokee, Object value) {
            try {
                
                super.getWriteMethod().invoke(
                        dynamicAccessor.getPropertyValue( invokee )
                        , new Object[]{index, value});
            } catch (IllegalArgumentException e) {
                throw new RuntimeException(e);
            } catch (IllegalAccessException e) {
                throw new RuntimeException(e);
            } catch (InvocationTargetException e) {
                throw new RuntimeException(e);
            } 
        }

        
    }
    private Map<String, List<ColumnHandler>> configMapping;
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
        
    	List<String> keys=Arrays.asList( config.split( "\\s*;\\s*") );
        
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
            final Class  clazz = dynamic.getClass();

            List<PropertyAccessor> pas=new ArrayList<PropertyAccessor>();
            if( !clazz.getName().equals("routines.system.Dynamic") ){
                return Collections.emptyList();
            }
            final int count= (int) clazz.getMethod("getColumnCount", new Class[]{}).invoke( dynamic, new Object[]{} );
            final Field metadatas = clazz.getField("metadatas");
            final Method setColumnValue = clazz.getMethod("setColumnValue", new Class[]{ int.class, Object.class });
            final Method getColumnValue = clazz.getMethod("getColumnValue", new Class[]{ int.class });
            final Method getMetadataByIndex=metadatas.get(dynamic).getClass().getMethod("get", new Class[]{ int.class });
            Method getRowname        =null;

            for(int i=0;i<count;i++){
                Object metadata=getMetadataByIndex.invoke(metadatas.get(dynamic), i);

                if(getRowname==null){
                    getRowname=metadata.getClass().getMethod("getName", new Class[]{});
                }
                DynamicsPropertyAccessor dpa=new DynamicsPropertyAccessor((String) getRowname.invoke(metadata, new Object[]{}), dynamicAccessor, i);
                dpa.setReadMethod(getColumnValue);
                dpa.setWriteMethod(setColumnValue);
                pas.add(dpa);
            }
            return pas;
        } catch (Throwable ex) {
            ex.printStackTrace();
            throw new RuntimeException(ex);
        }
        
    }

    private Map<String, PropertyAccessor> getAccessorMapping(Object row){
        List<PropertyAccessor> inputIntrospect = Introspector.introspect( row.getClass() );
        Map<String, PropertyAccessor> propertyMapping = new HashMap<String, PropertyAccessor>();

        for(PropertyAccessor pa : inputIntrospect){
            String relatedProp= casesensitive ? pa.getName() : pa.getName().toUpperCase();
            if( pa.getPropertyType().getName().equals("routines.system.Dynamic") ){
                List<PropertyAccessor> exposed=exposeDynamicRows( row, pa );
                for(PropertyAccessor dpa : exposed) {
                    relatedProp= casesensitive ? dpa.getName() : dpa.getName().toUpperCase();
                    propertyMapping.put(relatedProp, dpa);
                }
            } else {
                propertyMapping.put(relatedProp, pa);
            }
        }

        return propertyMapping;
        
    }
            
    public void createHashes(Object inputRow, Object outputRow, HashOutputEncoding encoding, HashFunction type) {
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
            Normalization norm=new Normalization(normalizeConfig);
            
            for(ColumnHandler col : configMapping.get(targetCol)){
                norm.add(  col.accessor.getPropertyValue(inputRow), col.createNormalizeObjectConfig() );
            }
            
            outputCache.get(targetCol).setPropertyValue(outputRow, norm.calculateHash(  type, encoding));
        }
            
    }

}
