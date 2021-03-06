package org.hcjf.layers.query;

/**
 * This interface provides the functionality to extends the instance domain.
 * @author javaito.
 *
 */
public interface Enlarged {

    /**
     * Return the value that corresponds to the specific field name.
     * @param fieldName Field name.
     * @return Field value.
     */
    Object get(String fieldName);

    /**
     * Add a new value to the instance.
     * @param key Name of the value.
     * @param value Value instance.
     * @return Return the value added.
     */
    Object put(String key, Object value);

    /**
     * Clone th enlarged object.
     * @return Enlarged clone.
     */
    Enlarged clone();

    /**
     * Clone the enlarged object without domain information.
     * @return Enlarged clone.
     */
    Enlarged cloneEmpty();
}
