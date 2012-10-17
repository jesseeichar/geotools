/*
 *    GeoTools - The Open Source Java GIS Toolkit
 *    http://geotools.org
 *
 *    (C) 2002-2009, Open Source Geospatial Foundation (OSGeo)
 *
 *    This library is free software; you can redistribute it and/or
 *    modify it under the terms of the GNU Lesser General Public
 *    License as published by the Free Software Foundation;
 *    version 2.1 of the License.
 *
 *    This library is distributed in the hope that it will be useful,
 *    but WITHOUT ANY WARRANTY; without even the implied warranty of
 *    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 *    Lesser General Public License for more details.
 */
package org.geotools.data.postgis;

import java.io.IOException;
import java.util.Date;

import org.geotools.data.jdbc.FilterToSQL;
import org.geotools.filter.FilterCapabilities;
import org.geotools.filter.LikeFilterImpl;
import org.geotools.jdbc.JDBCDataStore;
import org.opengis.feature.type.AttributeDescriptor;
import org.opengis.feature.type.GeometryDescriptor;
import org.opengis.filter.PropertyIsLike;
import org.opengis.filter.expression.Expression;
import org.opengis.filter.expression.Function;
import org.opengis.filter.expression.Literal;
import org.opengis.filter.expression.PropertyName;
import org.opengis.filter.spatial.BinarySpatialOperator;

import com.vividsolutions.jts.geom.Geometry;
import com.vividsolutions.jts.geom.LinearRing;

/**
 * 
 *
 * @source $URL$
 */
public class PostgisFilterToSQL extends FilterToSQL {

    FilterToSqlHelper helper;
    private boolean functionEncodingEnabled;

    public PostgisFilterToSQL(PostGISDialect dialect) {
        helper = new FilterToSqlHelper(this);
    }

    public boolean isLooseBBOXEnabled() {
        return helper.looseBBOXEnabled;
    }

    public void setLooseBBOXEnabled(boolean looseBBOXEnabled) {
        helper.looseBBOXEnabled = looseBBOXEnabled;
    }

    @Override
    protected void visitLiteralGeometry(Literal expression) throws IOException {
        // evaluate the literal and store it for later
        Geometry geom  = (Geometry) evaluateLiteral(expression, Geometry.class);
        
        if ( geom instanceof LinearRing ) {
            //postgis does not handle linear rings, convert to just a line string
            geom = geom.getFactory().createLineString(((LinearRing) geom).getCoordinateSequence());
        }
        
        Object typename = currentGeometry.getUserData().get(JDBCDataStore.JDBC_NATIVE_TYPENAME);
        if("geography".equals(typename)) {
            out.write("ST_GeogFromText('");
            out.write(geom.toText());
            out.write("')");
        } else {
            out.write("ST_GeomFromText('");
            out.write(geom.toText());
            if(currentSRID == null && currentGeometry  != null) {
                // if we don't know at all, use the srid of the geometry we're comparing against
                // (much slower since that has to be extracted record by record as opposed to 
                // being a constant)
                out.write("', ST_SRID(\"" + currentGeometry.getLocalName() + "\"))");
            } else {
                out.write("', " + currentSRID + ")");
            }
        }
    }

    @Override
    protected FilterCapabilities createFilterCapabilities() {
        return helper.createFilterCapabilities(functionEncodingEnabled);
    }

    protected Object visitBinarySpatialOperator(BinarySpatialOperator filter,
            PropertyName property, Literal geometry, boolean swapped,
            Object extraData) {
        helper.out = out;
        return helper.visitBinarySpatialOperator(filter, property, geometry,
                swapped, extraData);
    }
    
    protected Object visitBinarySpatialOperator(BinarySpatialOperator filter, Expression e1, 
            Expression e2, Object extraData) {
        helper.out = out;
        return helper.visitBinarySpatialOperator(filter, e1, e2, extraData);
    }

    GeometryDescriptor getCurrentGeometry() {
        return currentGeometry;
    }



    @Override
    public Object visit(Function function, Object extraData) throws RuntimeException {
        helper.out = out;
        try {
            encodingFunction = true;
            boolean encoded = helper.visitFunction(function, extraData);
            encodingFunction = false;
            
            if(encoded) {
               return extraData; 
            } else {
                return super.visit(function, extraData);
            }
        } catch(IOException e) {
            throw new RuntimeException(e);
        }
    }
    
    @Override
    protected String getFunctionName(Function function) {
        return helper.getFunctionName(function);
    }
    
    @Override
    protected String cast(String encodedProperty, Class target) throws IOException {
        return helper.cast(encodedProperty, target);
    }

    public void setFunctionEncodingEnabled(boolean functionEncodingEnabled) {
        this.functionEncodingEnabled = functionEncodingEnabled;
    }
    
    @Override
    public Object visit(PropertyIsLike filter, Object extraData){
        char esc = filter.getEscape().charAt(0);
        char multi = filter.getWildCard().charAt(0);
        char single = filter.getSingleChar().charAt(0);
        boolean matchCase = filter.isMatchingCase();

        String literal = filter.getLiteral();
        Expression att = filter.getExpression();
        if (att.toString().endsWith("_keylist")) {
            try {
                String pattern = LikeFilterImpl.convertToSQL92(esc, multi, single, matchCase, literal);
                out.write(" ( ");
                out.write(att.toString() + "_vectorise");
                // Debug tb 28.01.2010
                out.write(" @@ to_tsquery('english','");
                // end debug
                // Debug tb 07.12.2009
                // out.write(pattern.replaceAll("%", ""));
                pattern.replaceAll("%", "");
                if (pattern.startsWith(" ")) {
                    pattern = pattern.substring(1);
                }
                if (pattern.endsWith(" ")) {
                    pattern = pattern.substring(0, pattern.length() - 1);
                }
                out.write(pattern.replaceAll(" ", " & "));
                // end debug
                out.write("') ) ");
            } catch (java.io.IOException ioe) {
                throw new RuntimeException(IO_ERROR, ioe);
            }
        } else {
            // JD: hack for date values, we append some additional padding to
            // handle
            // the matching of time/timezone/etc...
            AttributeDescriptor ad = (AttributeDescriptor) att.evaluate(featureType);
            if (ad != null && Date.class.isAssignableFrom(ad.getType().getBinding())) {
                literal += multi;
            }

            String pattern = LikeFilterImpl.convertToSQL92(esc, multi, single, matchCase, literal);

            try {
                if (!matchCase) {
                    out.write(" UPPER(");
                }

                att.accept(this, extraData);

                if (!matchCase) {
                    out.write(") LIKE '");
                } else {
                    out.write(" LIKE '");
                }

                out.write(pattern);
                out.write("' ");
            } catch (java.io.IOException ioe) {
                throw new RuntimeException(IO_ERROR, ioe);
            }
        }
        return extraData;
    }

}
