package org.hcjf.layers.query;

import org.hcjf.properties.SystemProperties;

import java.util.regex.Pattern;

/**
 * @author javaito
 *
 */
public class Like extends FieldEvaluator {

    public Like(Object leftValue, Object rightValue) {
        super(leftValue, rightValue);
    }

    public Like(String fieldName, Object value) {
        super(new Query.QueryField(fieldName), value);
    }

    @Override
    public boolean evaluate(Object object, Queryable.DataSource dataSource, Queryable.Consumer consumer) {
        boolean result;

        try {
            Object leftValue = getProcessedLeftValue(object, dataSource, consumer);
            Object rightValue = getProcessedRightValue(object, dataSource, consumer);
            if(leftValue instanceof String) {
                if(rightValue instanceof Pattern) {
                    result = ((Pattern)rightValue).matcher((String)leftValue).matches();
                } else if(rightValue instanceof String) {
                    String stringFieldValue = (String) leftValue;
                    String stringValue = (String) rightValue;
                    String wildcard = SystemProperties.get(SystemProperties.Query.ReservedWord.LIKE_WILDCARD);
                    if(stringValue.startsWith(wildcard)) {
                        if(stringValue.endsWith(wildcard)) {
                            result = stringFieldValue.toUpperCase().contains(stringValue.toUpperCase().substring(1,stringValue.length()-1));
                        } else {
                            result = stringFieldValue.toUpperCase().endsWith(stringValue.toUpperCase().substring(1));
                        }
                    } else if(stringValue.endsWith(wildcard)) {
                        result = stringFieldValue.toUpperCase().startsWith(stringValue.toUpperCase().substring(0,stringValue.length()-1));
                    } else {
                        result = stringFieldValue.toUpperCase().contains(stringValue.toUpperCase());
                    }
                } else {
                    throw new IllegalArgumentException("The right value in the like operation must be a string or pattern");
                }
            } else {
                throw new IllegalArgumentException("The left value in the like operation must be a string");
            }
        } catch (Exception ex) {
            throw new IllegalArgumentException("Like evaluator fail", ex);
        }

        return result;
    }
}
