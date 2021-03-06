package org.hcjf.layers.query;

import org.hcjf.bson.BsonDocument;
import org.hcjf.layers.Layer;
import org.hcjf.layers.LayerInterface;
import org.hcjf.layers.Layers;
import org.hcjf.layers.crud.IdentifiableLayerInterface;
import org.hcjf.layers.query.functions.*;
import org.hcjf.properties.SystemProperties;
import org.hcjf.service.Service;
import org.hcjf.service.ServiceSession;
import org.hcjf.service.ServiceThread;
import org.hcjf.utils.NamedUuid;
import org.hcjf.utils.Strings;
import org.hcjf.utils.bson.BsonCustomBuilderLayer;
import org.hcjf.utils.bson.BsonParcelable;

import java.text.ParseException;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.MatchResult;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * This class contains all the parameter needed to create a query.
 * This kind of queries works over any data collection.
 * @author javaito
 */
public class Query extends EvaluatorCollection implements Queryable {

    private static final String QUERY_BSON_FIELD_NAME = "__query__";

    private final QueryId id;
    private final QueryResource resource;
    private Integer limit;
    private Integer start;
    private final List<QueryReturnParameter> groupParameters;
    private final List<QueryOrderParameter> orderParameters;
    private final List<QueryReturnParameter> returnParameters;
    private final List<Join> joins;
    private boolean returnAll;
    private String stringRepresentation;

    static {
        //Publishing default function layers...
        Layers.publishLayer(MathQueryFunctionLayer.class);
        Layers.publishLayer(StringQueryFunctionLayer.class);
        Layers.publishLayer(DateQueryFunctionLayer.class);
        Layers.publishLayer(ReferenceFunctionLayer.class);
        Layers.publishLayer(BsonQueryFunctionLayer.class);
        Layers.publishLayer(CollectionQueryFunction.class);
        Layers.publishLayer(ObjectQueryFunction.class);
        Layers.publishLayer(QueryBsonBuilderLayer.class);

        //Publishing default aggregate function layers...
        Layers.publishLayer(CountQueryAggregateFunctionLayer.class);
        Layers.publishLayer(SumAggregateFunctionLayer.class);
        Layers.publishLayer(ProductAggregateFunctionLayer.class);
        Layers.publishLayer(MeanAggregateFunctionLayer.class);
    }

    public Query(String resource, QueryId id) {
        this.id = id;
        this.groupParameters = new ArrayList<>();
        this.orderParameters = new ArrayList<>();
        this.returnParameters = new ArrayList<>();
        this.joins = new ArrayList<>();
        this.resource = new QueryResource(resource);
    }

    public Query(String resource){
        this(resource, new QueryId());
    }

    private Query(Query source) {
        super(source);
        this.id = new QueryId();
        this.resource = source.resource;
        this.limit = source.limit;
        this.start = source.start;
        this.returnAll = source.returnAll;
        this.orderParameters = new ArrayList<>();
        this.orderParameters.addAll(source.orderParameters);
        this.returnParameters = new ArrayList<>();
        this.returnParameters.addAll(source.returnParameters);
        this.groupParameters = new ArrayList<>();
        this.groupParameters.addAll(source.groupParameters);
        this.joins = new ArrayList<>();
        this.joins.addAll(source.joins);
    }

    private QueryParameter checkQueryParameter(QueryParameter queryParameter) {
        if(queryParameter instanceof QueryField) {
            QueryField queryField = (QueryField) queryParameter;
            QueryResource resource = queryField.getResource();
            if (resource == null) {
                queryField.setResource(getResource());
            }
        } else if(queryParameter instanceof QueryFunction) {
            QueryFunction function = (QueryFunction) queryParameter;
            for(Object functionParameter : function.getParameters()) {
                if(functionParameter instanceof QueryParameter) {
                    checkQueryParameter((QueryParameter) functionParameter);
                }
            }
        }
        return queryParameter;
    }

    @Override
    protected Evaluator checkEvaluator(Evaluator evaluator) {
        if(evaluator instanceof FieldEvaluator) {
            FieldEvaluator fieldEvaluator = (FieldEvaluator) evaluator;
            if(fieldEvaluator.getLeftValue() instanceof QueryParameter) {
                checkQueryParameter((QueryParameter) fieldEvaluator.getLeftValue());
            }
            if(fieldEvaluator.getRightValue() instanceof QueryParameter) {
                checkQueryParameter((QueryParameter) fieldEvaluator.getRightValue());
            }
        }
        return evaluator;
    }

    /**
     * Verify if the query indicates return all the fields of the result set.
     * @return Return all.
     */
    public final boolean returnAll() {
        return returnAll || returnParameters.isEmpty();
    }

    /**
     * Returns the parameterized query based in this instance of query.
     * @return Parameterized query instance.
     */
    public final ParameterizedQuery getParameterizedQuery() {
        return new ParameterizedQuery(this);
    }

    /**
     * Return the id of the query.
     * @return Id of the query.
     */
    public final QueryId getId() {
        return id;
    }

    /**
     * Return the list of joins.
     * @return Joins.
     */
    public List<Join> getJoins() {
        return Collections.unmodifiableList(joins);
    }

    /**
     * Return the resource query object.
     * @return Resource query.
     */
    public QueryResource getResource() {
        return resource;
    }

    /**
     * Return the resource name.
     * @return Resource name.
     */
    public final String getResourceName() {
        return resource.getResourceName();
    }

    /**
     * Return the limit of the query.
     * @return Query limit.
     */
    public final Integer getLimit() {
        return limit;
    }

    /**
     * Set the query limit.
     * @param limit Query limit.
     */
    public final void setLimit(Integer limit) {
        this.limit = limit;
    }

    /**
     * Return the object that represents the first element of the result.
     * @return Firts object of the result.
     */
    public final Integer getStart() {
        return start;
    }

    /**
     * Set the first object of the result.
     * @param start First object of the result.
     */
    public final void setStart(Integer start) {
        this.start = start;
    }

    /**
     * Return all the group fields of the query.
     * @return Group field of the query.
     */
    public List<QueryReturnParameter> getGroupParameters() {
        return Collections.unmodifiableList(groupParameters);
    }

    /**
     * Add a name of the field for group the data collection. This name must be exist
     * like a setter/getter method in the instances of the data collection.
     * @param groupField Name of the pair getter/setter.
     * @return Return the same instance of this class.
     */
    public final Query addGroupField(String groupField) {
        return addGroupField(new QueryReturnField(groupField));
    }

    /**
     * Add a name of the field for group the data collection. This name must be exist
     * like a setter/getter method in the instances of the data collection.
     * @param groupField Name of the pair getter/setter.
     * @return Return the same instance of this class.
     */
    public final Query addGroupField(QueryReturnParameter groupField) {
        groupParameters.add((QueryReturnParameter)checkQueryParameter((QueryParameter) groupField));
        return this;
    }

    /**
     * Return the unmodifiable list with order fields.
     * @return Order fields.
     */
    public final List<QueryOrderParameter> getOrderParameters() {
        return Collections.unmodifiableList(orderParameters);
    }

    /**
     * Add a name of the field for order the data collection. This name must be exist
     * like a setter/getter method in the instances of the data collection.
     * @param orderField Name of the pair getter/setter.
     * @return Return the same instance of this class.
     */
    public final Query addOrderField(String orderField) {
        return addOrderField(orderField, SystemProperties.getBoolean(SystemProperties.Query.DEFAULT_DESC_ORDER));
    }

    /**
     * Add a name of the field for order the data collection. This name must be exist
     * like a setter/getter method in the instances of the data collection.
     * @param orderField Name of the pair getter/setter.
     * @param desc Desc property.
     * @return Return the same instance of this class.
     */
    public final Query addOrderField(String orderField, boolean desc) {
        return addOrderField(new QueryOrderField(orderField, desc));
    }

    /**
     * Add a name of the field for order the data collection. This name must be exist
     * like a setter/getter method in the instances of the data collection.
     * @param orderParameter Order parameter.
     * @return Return the same instance of this class.
     */
    public final Query addOrderField(QueryOrderParameter orderParameter) {
        orderParameters.add((QueryOrderParameter) checkQueryParameter((QueryParameter) orderParameter));
        return this;
    }

    /**
     * Return an unmodifiable list with the return fields.
     * @return Return fields.
     */
    public final List<QueryReturnParameter> getReturnParameters() {
        return Collections.unmodifiableList(returnParameters);
    }

    /**
     * Add the name of the field to be returned in the result set.
     * @param returnField Field name.
     * @return Return the same instance of this class.
     */
    public final Query addReturnField(String returnField) {
        if(returnField.equals(SystemProperties.get(SystemProperties.Query.ReservedWord.RETURN_ALL))) {
            returnAll = true;
        } else {
            addReturnField(new QueryReturnField(returnField));
        }
        return this;
    }

    /**
     * Add the name of the field to be returned in the result set.
     * @param returnParameter Return parameter.
     * @return Return the same instance of this class.
     */
    public final Query addReturnField(QueryReturnParameter returnParameter) {
        if(returnParameter instanceof QueryReturnField && ((QueryReturnField)returnParameter).getFieldName().equals(
                SystemProperties.get(SystemProperties.Query.ReservedWord.RETURN_ALL))) {
            returnAll = true;
        } else {
            returnParameters.add((QueryReturnParameter) checkQueryParameter((QueryParameter) returnParameter));
        }
        return this;
    }

    /**
     * Add join instance to the query.
     * @param join Join instance.
     */
    public final void addJoin(Join join) {
        if(join != null && !joins.contains(join)) {
            joins.add(join);
        } else {
            if(join == null) {
                throw new NullPointerException("Null join instance");
            }
        }
    }

    /**
     * This method evaluate each object of the collection and sort filtered
     * object to create a result add with the object filtered and sorted.
     * If there are order fields added then the result implementation is a
     * {@link TreeSet} implementation else the result implementation is a
     * {@link LinkedHashSet} implementation in order to guarantee the data order
     * from the source
     * @param dataSource Data source to evaluate the query.
     * @param <O> Kind of instances of the data collection.
     * @return Result add filtered and sorted.
     */
    @Override
    public final <O extends Object> Collection<O> evaluate(Collection<O> dataSource) {
        return evaluate((query) -> dataSource, new Queryable.IntrospectionConsumer<>());
    }

    /**
     * This method evaluate each object of the collection and sort filtered
     * object to create a result add with the object filtered and sorted.
     * If there are order fields added then the result implementation is a
     * {@link TreeSet} implementation else the result implementation is a
     * {@link LinkedHashSet} implementation in order to guarantee the data order
     * from the source
     * @param dataSource Data source to evaluate the query.
     * @param consumer Data source consumer.
     * @param <O> Kind of instances of the data collection.
     * @return Result add filtered and sorted.
     */
    @Override
    public final <O extends Object> Collection<O> evaluate(Collection<O> dataSource, Queryable.Consumer<O> consumer) {
        return evaluate((query) -> dataSource, consumer);
    }

    /**
     * This method evaluate each object of the collection and sort filtered
     * object to create a result add with the object filtered and sorted.
     * If there are order fields added then the result implementation is a
     * {@link TreeSet} implementation else the result implementation is a
     * {@link LinkedHashSet} implementation in order to guarantee the data order
     * from the source
     * @param dataSource Data source to evaluate the query.
     * @param <O> Kind of instances of the data collection.
     * @return Result add filtered and sorted.
     */
    @Override
    public final <O extends Object> Collection<O> evaluate(Queryable.DataSource<O> dataSource) {
        return evaluate(dataSource, new Queryable.IntrospectionConsumer<>());
    }

    /**
     * This method evaluate each object of the collection and sort filtered
     * object to create a result add with the object filtered and sorted.
     * If there are order fields added then the result implementation is a
     * {@link TreeSet} implementation else the result implementation is a
     * {@link LinkedHashSet} implementation in order to guarantee the data order
     * from the source
     * @param dataSource Data source to evaluate the query.
     * @param consumer Data source consumer.
     * @param <O> Kind of instances of the data collection.
     * @return Result add filtered and sorted.
     */
    @Override
    public final <O extends Object> Collection<O> evaluate(Queryable.DataSource<O> dataSource, Queryable.Consumer<O> consumer) {
        Collection<O> result;
        Map<String, Groupable> groupables = null;
        List<QueryReturnFunction> aggregateFunctions = new ArrayList<>();
        if(!(Thread.currentThread() instanceof ServiceThread)) {
            //If the current thread is not a service thread then we call this
            //method again using a service thread.
            result = Service.call(()->evaluate(dataSource, consumer), ServiceSession.getGuestSession());
        } else {

            //Initialize the evaluators cache because the evaluators in the simple
            //query are valid into the platform evaluation environment.
            initializeEvaluatorsCache();

            //Creating result data collection.
            if (orderParameters.size() > 0) {
                //If the query has order fields then creates a tree set with
                //a comparator using the order fields.
                result = new TreeSet<>((o1, o2) -> {
                    int compareResult = 0;

                    Comparable<Object> comparable1;
                    Comparable<Object> comparable2;
                    for (QueryOrderParameter orderField : orderParameters) {
                        try {
                            if (orderField instanceof QueryOrderFunction) {
                                comparable1 = consumer.resolveFunction(((QueryOrderFunction) orderField), o1, dataSource);
                                comparable2 = consumer.resolveFunction(((QueryOrderFunction) orderField), o2, dataSource);
                            } else {
                                comparable1 = consumer.get(o1, (QueryParameter) orderField, dataSource);
                                comparable2 = consumer.get(o2, (QueryParameter) orderField, dataSource);
                            }
                        } catch (ClassCastException ex) {
                            throw new IllegalArgumentException("Order field must be comparable");
                        }

                        if (comparable1 == null ^ comparable2 == null) {
                            compareResult = (comparable1 == null) ? -1 : 1;
                        } else if (comparable1 == null && comparable2 == null) {
                            compareResult = 0;
                        } else {
                            compareResult = comparable1.compareTo(comparable2) * (orderField.isDesc() ? -1 : 1);
                        }

                        if (compareResult != 0) {
                            break;
                        }
                    }

                    if (compareResult == 0) {
                        compareResult = o1.hashCode() - o2.hashCode();
                    }

                    return compareResult;
                });
            } else {
                //If the query has not order fields then creates a linked hash set to
                //maintain the natural order of the data.
                result = new ArrayList<>();
            }

            //Getting data from data source.
            Collection<O> data;
            try {
                if (joins.size() > 0) {
                    //If the query has joins then data source must return the joined data
                    //collection using all the resources
                    data = (Collection<O>) join((Queryable.DataSource<Joinable>) dataSource, (Queryable.Consumer<Joinable>) consumer);
                } else {
                    //Creates the first query for the original resource.
                    Query resolveQuery = new Query(getResourceName());
                    resolveQuery.returnAll = true;
                    if (getStart() != null) {
                        resolveQuery.setLimit(getLimit() + getStart());
                    } else {
                        resolveQuery.setLimit(getLimit());
                    }
                    copyEvaluators(resolveQuery, this);

                    //If the query has not joins then data source must return data from
                    //resource of the query.
                    data = dataSource.getResourceData(verifyInstance(resolveQuery, consumer));
                }

                //Filtering data
                boolean add;
                int start = getStart() == null ? 0 : getStart();

                //Collect all the aggregate functions into the array list.
                for (QueryReturnParameter returnParameter : getReturnParameters()) {
                    if(returnParameter instanceof QueryReturnFunction && ((QueryReturnFunction)returnParameter).isAggregate()) {
                        aggregateFunctions.add((QueryReturnFunction) returnParameter);
                    }
                }

                if (start < data.size()) {

                    StringBuilder hashCode;
                    Groupable groupable;
                    Integer count = -1;
                    if (!groupParameters.isEmpty()) {
                        groupables = new HashMap<>();
                    }

                    for (O object : data) {
                        add = verifyCondition(object, dataSource, consumer);
                        if (add) {
                            count++;
                            if(start > count) {
                                continue;
                            }
                            if (object instanceof Enlarged) {
                                Enlarged originalObject = ((Enlarged) object).clone();
                                Enlarged enlargedObject;
                                if(returnAll || !aggregateFunctions.isEmpty()) {
                                    enlargedObject = originalObject;
                                } else {
                                    enlargedObject = originalObject.cloneEmpty();
                                }
                                object = (O) enlargedObject;
                                String name;
                                Object value;
                                for (QueryReturnParameter returnParameter : getReturnParameters()) {
                                    name = null;
                                    value = null;
                                    if (returnParameter instanceof QueryReturnField) {
                                        QueryReturnField returnField = (QueryReturnField) returnParameter;
                                        name = returnField.getAlias() != null ? returnField.getAlias() : returnField.getFieldName();
                                        value = originalObject.get(returnField.getFieldName());
                                    } else if (returnParameter instanceof QueryReturnFunction && !((QueryReturnFunction)returnParameter).isAggregate()) {
                                        QueryReturnFunction function = (QueryReturnFunction) returnParameter;
                                        name = function.getAlias() == null ? function.toString() : function.getAlias();
                                        value = consumer.resolveFunction(function, originalObject, dataSource);
                                    }
                                    if(name != null && value != null) {
                                        originalObject.put(name, value);
                                        enlargedObject.put(name, value);
                                    }
                                }
                            }

                            if (!groupParameters.isEmpty() && object instanceof Groupable) {
                                groupable = (Groupable) object;
                                hashCode = new StringBuilder();
                                for (QueryReturnParameter returnParameter : groupParameters) {
                                    if (returnParameter instanceof QueryReturnField) {
                                        hashCode.append(consumer.get(object, ((QueryReturnField) returnParameter), dataSource).hashCode());
                                    } else {
                                        hashCode.append(consumer.resolveFunction(((QueryReturnFunction) returnParameter), object, dataSource).hashCode());
                                    }
                                }
                                if (groupables.containsKey(hashCode.toString())) {
                                    groupables.get(hashCode.toString()).group(groupable);
                                } else {
                                    groupables.put(hashCode.toString(), groupable);
                                }
                            } else {
                                result.add(object);
                            }
                        }
                        if (getLimit() != null && result.size() == (start + getLimit())) {
                            break;
                        }
                    }

                    if(groupables != null) {
                        result.addAll((Collection<? extends O>) groupables.values());
                    }
                }
            } finally {
                clearEvaluatorsCache();
            }
        }

        if(aggregateFunctions.size() > 0) {
            for (QueryReturnFunction function : aggregateFunctions) {
                result = consumer.resolveFunction(function, result, dataSource);
            }
        }

        return result;
    }

    private Queryable verifyInstance(Query query, Consumer consumer) {
        Queryable result = query;
        if(consumer instanceof ParameterizedQuery.ParameterizedConsumer &&
                ((ParameterizedQuery.ParameterizedConsumer)consumer).getParameters().size() > 0) {
            ParameterizedQuery parameterizedQuery = query.getParameterizedQuery();
            for(Object parameter : ((ParameterizedQuery.ParameterizedConsumer)consumer).getParameters()) {
                parameterizedQuery.add(parameter);
            }
            result = parameterizedQuery;
        }
        return result;
    }

    /**
     * This method verify if the conditions of the query are true or not.
     * @param object Object to use as condition parameters.
     * @return Returns if the evaluation of conditions are true or false in the otherwise.
     */
    public final boolean verifyCondition(Object object) {
        Consumer consumer = new Queryable.IntrospectionConsumer<>();
        Collection collection = List.of(object);
        return verifyCondition(object, Q->collection, consumer);
    }

    /**
     * This method verify if the conditions of the query are true or not.
     * @param object Object to use as condition parameters.
     * @param dataSource Data source.
     * @param consumer Consumer.
     * @return Returns if the evaluation of conditions are true or false in the otherwise.
     */
    private boolean verifyCondition(Object object, DataSource dataSource, Consumer consumer) {
        Boolean result = true;
        for (Evaluator evaluator : getEvaluators()) {
            if (!isEvaluatorDone(evaluator)) {
                result &= evaluator.evaluate(object, dataSource, consumer);
                if (!result) {
                    break;
                }
            }
        }
        return result;
    }

    /**
     * This method check if the evaluator is evaluated previously into the current session.
     * @param evaluator Checking evaluator.
     * @return Return true if the evaluator is done and false in the otherwise.
     */
    private boolean isEvaluatorDone(Evaluator evaluator) {
        boolean result = false;

        ServiceSession session = ServiceSession.getCurrentSession();
        if(session != null) {
            List<Evaluator> evaluatorsCache = (List<Evaluator>) session.getProperties().get(
                    SystemProperties.get(SystemProperties.Query.EVALUATORS_CACHE_NAME));
            if(evaluatorsCache != null) {
                result = evaluatorsCache.contains(evaluator);
            }
        }

        return result;
    }

    /**
     * Initialize the evaluators cache into the current session.
     */
    private void initializeEvaluatorsCache() {
        ServiceSession session = ServiceSession.getCurrentIdentity();
        if(session != null) {
            session.put(SystemProperties.get(SystemProperties.Query.EVALUATORS_CACHE_NAME),
                    new ArrayList<Evaluator>());
            session.put(SystemProperties.get(SystemProperties.Query.EVALUATOR_LEFT_VALUES_CACHE_NAME),
                    new HashMap<Evaluator, Object>());
            session.put(SystemProperties.get(SystemProperties.Query.EVALUATOR_RIGHT_VALUES_CACHE_NAME),
                    new HashMap<Evaluator, Object>());
        }
    }

    /**
     * Removes the evaluators cache of the current session.
     */
    private void clearEvaluatorsCache() {
        ServiceSession session = ServiceSession.getCurrentIdentity();
        if(session != null) {
            session.remove(SystemProperties.get(SystemProperties.Query.EVALUATORS_CACHE_NAME));
            session.remove(SystemProperties.get(SystemProperties.Query.EVALUATOR_LEFT_VALUES_CACHE_NAME));
            session.remove(SystemProperties.get(SystemProperties.Query.EVALUATOR_RIGHT_VALUES_CACHE_NAME));
        }
    }

    /**
     * This method add into the current session an instance that must be skipped of the
     * platform evaluation process.
     * @param evaluator Evaluator to skip.
     */
    public static void skipEvaluator(Evaluator evaluator) {
        ServiceSession session = ServiceSession.getCurrentSession();
        if(session != null) {
            List<Evaluator> evaluatorsCache = (List<Evaluator>) session.getProperties().get(
                    SystemProperties.get(SystemProperties.Query.EVALUATORS_CACHE_NAME));
            if(evaluatorsCache != null) {
                evaluatorsCache.add(evaluator);
            }
        }
    }

    /**
     * Copy all the evaluator from the source collection to destiny collection.
     * @param dest Destiny collection.
     * @param src Source collection.
     */
    private void copyEvaluators(EvaluatorCollection dest, EvaluatorCollection src) {
        for(Evaluator evaluator : src.getEvaluators()) {
            if(evaluator instanceof FieldEvaluator) {
                dest.addEvaluator(((FieldEvaluator)evaluator).copy());
            } else if(evaluator instanceof And) {
                copyEvaluators(dest.and(), (EvaluatorCollection) evaluator);
            } else if(evaluator instanceof Or) {
                copyEvaluators(dest.or(), (EvaluatorCollection) evaluator);
            }
        }
    }

    /**
     * Create a joined data from data source using the joins instances stored in the query.
     * @param dataSource Data souce.
     * @param consumer Consumer.
     * @return Joined data collection.
     */
    private Collection<Joinable> join(Queryable.DataSource<Joinable> dataSource, Queryable.Consumer<Joinable> consumer) {
        Collection<Joinable> result = new ArrayList<>();

        //Creates the first query for the original resource.
        Query joinQuery = new Query(getResourceName());
        joinQuery.addReturnField(SystemProperties.get(SystemProperties.Query.ReservedWord.RETURN_ALL));
        for(Evaluator evaluator : getEvaluatorsFromResource(this, joinQuery, getResource())) {
            joinQuery.addEvaluator(((FieldEvaluator)evaluator).copy());
        }
        //Set the first query as start by default
        Query startQuery = joinQuery;

        //Put the first query in the list
        List<Queryable> queries = new ArrayList<>();
        queries.add(verifyInstance(joinQuery, consumer));

        //Build a query for each join and evaluate the better match to start
        int queryStart = 0;
        int joinStart = 0;
        for (int i = 0; i < joins.size(); i++) {
            Join join = joins.get(i);
            joinQuery = new Query(join.getResourceName());
            joinQuery.addReturnField(SystemProperties.get(SystemProperties.Query.ReservedWord.RETURN_ALL));
            for (Evaluator evaluator : join.getEvaluators()) {
                joinQuery.addEvaluator(evaluator);
            }
            for (Evaluator evaluator : getEvaluatorsFromResource(this, joinQuery, join.getResource())) {
                joinQuery.addEvaluator(evaluator);
            }
            queries.add(verifyInstance(joinQuery, consumer));

            if(joinQuery.getEvaluators().size() > startQuery.getEvaluators().size()) {
                startQuery = joinQuery;
                queryStart = i+1;
                joinStart = i;
            }
        }

        Map<Object, Set<Joinable>> indexedJoineables;
        Collection<Joinable> leftJoinables = new ArrayList<>();
        Collection<Joinable> rightJoinables = new ArrayList<>();
        Join queryJoin;
        Set<Object> keys;
        QueryField firstField;
        QueryField secondField;
        Queryable currentQueryable;

        //Evaluate from the start query to right
        int j = joinStart;
        for (int i = queryStart; i < queries.size(); i++) {
            currentQueryable = queries.get(i);
            if(leftJoinables.isEmpty()) {
                leftJoinables.addAll(dataSource.getResourceData(currentQueryable));
            } else {
                queryJoin = joins.get(j);

                if(queryJoin.getLeftField().getResource().equals(queryJoin.getResource())) {
                    //If the left field of the join has the same resource that join then the
                    //right field index the accumulated data.
                    firstField = queryJoin.getRightField();
                    secondField = queryJoin.getLeftField();
                } else {
                    //If the right field of the join has the same resource that join then the
                    //left field index the accumulated data.
                    firstField = queryJoin.getLeftField();
                    secondField = queryJoin.getRightField();
                }

                indexedJoineables = index(leftJoinables, firstField, consumer, dataSource);
                leftJoinables.clear();
                keys = indexedJoineables.keySet();
                if(currentQueryable instanceof ParameterizedQuery) {
                    ((ParameterizedQuery)currentQueryable).getQuery().addEvaluator(new In(secondField.toString(), keys));
                } else {
                    ((Query)currentQueryable).addEvaluator(new In(secondField.toString(), keys));
                }
                rightJoinables.addAll(dataSource.getResourceData(currentQueryable));
                for (Joinable rightJoinable : rightJoinables) {
                    Set<Joinable> joinables = indexedJoineables.get(consumer.get(rightJoinable, secondField, dataSource));
                    if(joinables != null) {
                        for (Joinable leftJoinable : joinables) {
                            leftJoinables.add(leftJoinable.join(rightJoinable));
                        }
                    }
                }
                j++;
            }
        }

        rightJoinables = leftJoinables;
        leftJoinables = new ArrayList<>();

        //Evaluate from the start query to left
        j = joinStart;
        for (int i = queryStart - 1; i >= 0; i--, j--) {
            currentQueryable = queries.get(i);
            queryJoin = joins.get(j);

            if(queryJoin.getLeftField().getResource().equals(queryJoin.getResource())) {
                //If the right field of the join has the same resource that join then the
                //left field index the accumulated data.
                firstField = queryJoin.getLeftField();
                secondField = queryJoin.getRightField();
            } else {
                //If the left field of the join has the same resource that join then the
                //right field index the accumulated data.
                firstField = queryJoin.getRightField();
                secondField = queryJoin.getLeftField();
            }

            indexedJoineables = index(rightJoinables, firstField, consumer, dataSource);
            rightJoinables.clear();
            keys = indexedJoineables.keySet();
            if(currentQueryable instanceof ParameterizedQuery) {
                ((ParameterizedQuery)currentQueryable).getQuery().addEvaluator(new In(secondField.toString(), keys));
            } else {
                ((Query)currentQueryable).addEvaluator(new In(secondField.toString(), keys));
            }
            leftJoinables.addAll(dataSource.getResourceData(currentQueryable));
            for (Joinable leftJoinable : leftJoinables) {
                Set<Joinable> joinables = indexedJoineables.get(consumer.get(leftJoinable, secondField, dataSource));
                if(joinables != null) {
                    for (Joinable rightJoinable : joinables) {
                        rightJoinables.add(rightJoinable.join(leftJoinable));
                    }
                }
            }
        }

        result.addAll(rightJoinables);
        return result;
    }

    /**
     * Return the list of evaluator for the specific resource.
     * @param collection Evaluator collection.
     * @param resource Resource type.
     * @return List of evaluators.
     */
    private List<Evaluator> getEvaluatorsFromResource(EvaluatorCollection collection, EvaluatorCollection parent, QueryResource resource) {
        List<Evaluator> result = new ArrayList<>();
        for(Evaluator evaluator : collection.getEvaluators()) {
            if(evaluator instanceof FieldEvaluator) {
                FieldEvaluator fieldEvaluator = (FieldEvaluator) evaluator;
                QueryParameter queryParameter;
                boolean evaluatorAdded = false;

                if(fieldEvaluator.getLeftValue() instanceof QueryParameter) {
                    queryParameter = (QueryParameter) fieldEvaluator.getLeftValue();
                    if((queryParameter instanceof QueryField &&
                            ((QueryField)queryParameter).getResource().equals(resource)) ||
                            (queryParameter instanceof QueryFunction &&
                                    ((QueryFunction)queryParameter).getResources().contains(resource))){
                        result.add(evaluator);
                        evaluatorAdded = true;
                    }
                }

                if(!evaluatorAdded) {
                    if(fieldEvaluator.getRightValue() instanceof QueryParameter) {
                        queryParameter = (QueryParameter) fieldEvaluator.getRightValue();
                        if((queryParameter instanceof QueryField &&
                                ((QueryField)queryParameter).getResource().equals(resource)) ||
                                (queryParameter instanceof QueryFunction &&
                                        ((QueryFunction)queryParameter).getResources().contains(resource))){
                            result.add(evaluator);
                        }
                    }
                }
            } else if(evaluator instanceof EvaluatorCollection) {
                EvaluatorCollection subCollection = null;
                if(evaluator instanceof And) {
                    subCollection = new And(parent);
                } else if(evaluator instanceof Or) {
                    subCollection = new Or(parent);
                }
                for(Evaluator subEvaluator : getEvaluatorsFromResource((EvaluatorCollection)evaluator, subCollection, resource)) {
                    subCollection.addEvaluator(subEvaluator);
                }
            }
        }
        return result;
    }

    /**
     * This method evaluate all the values of the collection and put each of values
     * into a map indexed by the value of the parameter field.
     * @param objects Collection of data to evaluate.
     * @param fieldIndex Field to index result.
     * @param consumer Implementation to get the value from the collection
     * @return Return the filtered data indexed by value of the parameter field.
     */
    private final Map<Object, Set<Joinable>> index(Collection<Joinable> objects, QueryField fieldIndex, Consumer<Joinable> consumer, DataSource<Joinable> dataSource) {
        Map<Object, Set<Joinable>> result = new HashMap<>();

        Object key;
        Set<Joinable> set;
        for(Joinable joinable : objects) {
            key = consumer.get(joinable, fieldIndex, dataSource);
            result.computeIfAbsent(key, k -> new HashSet<>()).add(joinable);
        }

        return result;
    }

    /**
     * Return a copy of this query without all the evaluator and order fields of the
     * parameter collections.
     * @param evaluatorsToRemove Evaluators to reduce.
     * @return Reduced copy of the query.
     */
    public final Query reduce(Collection<Evaluator> evaluatorsToRemove) {
        Query copy = new Query(this);
        copy.evaluators.addAll(this.evaluators);

        if(evaluatorsToRemove != null && !evaluatorsToRemove.isEmpty()) {
            reduceCollection(copy, evaluatorsToRemove);
        }

        return copy;
    }


    public final Query reduceFieldEvaluator(String fieldName, Class<? extends FieldEvaluator>... evaluatorType) {
        return reduce(getFieldEvaluators(fieldName, evaluatorType));
    }

    /**
     * Reduce recursively all the collection into the query.
     * @param collection Collection to reduce.
     * @param evaluatorsToRemove Evaluator to remove.
     */
    private final void reduceCollection(EvaluatorCollection collection, Collection<Evaluator> evaluatorsToRemove) {
        for(Evaluator evaluatorToRemove : evaluatorsToRemove) {
            collection.evaluators.remove(evaluatorToRemove);
            collection.addEvaluator(new TrueEvaluator());
        }

        for(Evaluator evaluator : collection.evaluators) {
            if(evaluator instanceof Or || evaluator instanceof And) {
                reduceCollection((EvaluatorCollection)evaluator, evaluatorsToRemove);
            }
        }
    }

    /**
     * Creates a string representation of the query object.
     * @return String representation.
     */
    @Override
    public synchronized String toString() {
        if(stringRepresentation == null) {
            Strings.Builder resultBuilder = new Strings.Builder();

            //Print select
            resultBuilder.append(SystemProperties.get(SystemProperties.Query.ReservedWord.SELECT));
            resultBuilder.append(Strings.WHITE_SPACE);
            if (returnAll) {
                resultBuilder.append(SystemProperties.get(SystemProperties.Query.ReservedWord.RETURN_ALL));
                SystemProperties.get(SystemProperties.Query.ReservedWord.ARGUMENT_SEPARATOR);
                resultBuilder.append(Strings.WHITE_SPACE);
            }
            for (QueryReturnParameter field : getReturnParameters()) {
                resultBuilder.append(field);
                if (field.getAlias() != null) {
                    resultBuilder.append(Strings.WHITE_SPACE).append(SystemProperties.get(SystemProperties.Query.ReservedWord.AS));
                    resultBuilder.append(Strings.WHITE_SPACE).append(field.getAlias());
                }
                resultBuilder.append(Strings.EMPTY_STRING, SystemProperties.get(SystemProperties.Query.ReservedWord.ARGUMENT_SEPARATOR));
            }
            resultBuilder.cleanBuffer();

            //Print from
            resultBuilder.append(Strings.WHITE_SPACE);
            resultBuilder.append(SystemProperties.get(SystemProperties.Query.ReservedWord.FROM));
            resultBuilder.append(Strings.WHITE_SPACE);
            resultBuilder.append(getResourceName());
            resultBuilder.append(Strings.WHITE_SPACE);

            //Print joins
            for (Join join : joins) {
                if (!(join.getType() == Join.JoinType.JOIN)) {
                    resultBuilder.append(join.getType());
                    resultBuilder.append(Strings.WHITE_SPACE);
                }
                resultBuilder.append(SystemProperties.get(SystemProperties.Query.ReservedWord.JOIN)).append(Strings.WHITE_SPACE);
                resultBuilder.append(join.getResourceName()).append(Strings.WHITE_SPACE);
                resultBuilder.append(SystemProperties.get(SystemProperties.Query.ReservedWord.ON)).append(Strings.WHITE_SPACE);
                resultBuilder.append(join.getLeftField()).append(Strings.WHITE_SPACE);
                resultBuilder.append(SystemProperties.get(SystemProperties.Query.ReservedWord.EQUALS)).append(Strings.WHITE_SPACE);
                resultBuilder.append(join.getRightField()).append(Strings.WHITE_SPACE);
                if (join.getEvaluators().size() > 0) {
                    resultBuilder.append(SystemProperties.get(SystemProperties.Query.ReservedWord.AND)).append(Strings.WHITE_SPACE);
                    toStringEvaluatorCollection(resultBuilder, join);
                }
            }

            if (evaluators.size() > 0) {
                resultBuilder.append(SystemProperties.get(SystemProperties.Query.ReservedWord.WHERE)).append(Strings.WHITE_SPACE);
                toStringEvaluatorCollection(resultBuilder, this);
            }

            if (groupParameters.size() > 0) {
                resultBuilder.append(SystemProperties.get(SystemProperties.Query.ReservedWord.GROUP_BY)).append(Strings.WHITE_SPACE);
                for (QueryReturnParameter groupParameter : groupParameters) {
                    resultBuilder.append(groupParameter, SystemProperties.Query.ReservedWord.ARGUMENT_SEPARATOR);
                }
                resultBuilder.append(Strings.WHITE_SPACE);
                resultBuilder.cleanBuffer();
            }

            if (orderParameters.size() > 0) {
                resultBuilder.append(SystemProperties.get(SystemProperties.Query.ReservedWord.ORDER_BY)).append(Strings.WHITE_SPACE);
                for (QueryOrderParameter orderField : orderParameters) {
                    resultBuilder.append(orderField);
                    if (orderField.isDesc()) {
                        resultBuilder.append(Strings.WHITE_SPACE).append(SystemProperties.get(SystemProperties.Query.ReservedWord.DESC));
                    }
                    resultBuilder.append(Strings.EMPTY_STRING, SystemProperties.get(SystemProperties.Query.ReservedWord.ARGUMENT_SEPARATOR));
                }
                resultBuilder.cleanBuffer();
            }

            if (getStart() != null) {
                resultBuilder.append(Strings.WHITE_SPACE).append(SystemProperties.get(SystemProperties.Query.ReservedWord.START));
                resultBuilder.append(Strings.WHITE_SPACE).append(getStart());
            }

            if (getLimit() != null) {
                resultBuilder.append(Strings.WHITE_SPACE).append(SystemProperties.get(SystemProperties.Query.ReservedWord.LIMIT));
                resultBuilder.append(Strings.WHITE_SPACE).append(getLimit());
            }
            stringRepresentation = resultBuilder.toString();
        }

        return stringRepresentation;
    }

    /**
     * Creates a string representation of evaluator collection.
     * @param result Buffer with the current result.
     * @param collection Collection in order to create the string representation.
     */
    private void toStringEvaluatorCollection(Strings.Builder result, EvaluatorCollection collection) {
        String separator = Strings.EMPTY_STRING;
        String separatorValue = collection instanceof Or ?
                SystemProperties.get(SystemProperties.Query.ReservedWord.OR) :
                SystemProperties.get(SystemProperties.Query.ReservedWord.AND);
        for(Evaluator evaluator : collection.getEvaluators()) {
            if(evaluator instanceof Or) {
                if(!separator.isEmpty()) {
                    result.append(SystemProperties.get(SystemProperties.Query.ReservedWord.OR));
                }
                result.append(Strings.WHITE_SPACE);
                if(((Or)evaluator).getEvaluators().size() == 1) {
                    toStringEvaluatorCollection(result, (Or) evaluator);
                } else {
                    result.append(Strings.START_GROUP);
                    toStringEvaluatorCollection(result, (Or) evaluator);
                    result.append(Strings.END_GROUP);
                }
                result.append(Strings.WHITE_SPACE);
            } else if(evaluator instanceof And) {
                if(!separator.isEmpty()) {
                    result.append(SystemProperties.get(SystemProperties.Query.ReservedWord.AND));
                }
                result.append(Strings.WHITE_SPACE);
                if (collection instanceof Query) {
                    toStringEvaluatorCollection(result, (And) evaluator);
                } else {
                    if (((And) evaluator).getEvaluators().size() == 1) {
                        toStringEvaluatorCollection(result, (And) evaluator);
                    } else {
                        result.append(Strings.START_GROUP);
                        toStringEvaluatorCollection(result, (And) evaluator);
                        result.append(Strings.END_GROUP);
                    }
                }
                result.append(Strings.WHITE_SPACE);
            } else if(evaluator instanceof BooleanEvaluator) {
                result.append(separator);
                BooleanEvaluator booleanEvaluator = (BooleanEvaluator) evaluator;
                if(booleanEvaluator.isTrueForced()) {
                    result.append(Boolean.TRUE.toString());
                } else {
                    result = toStringFieldEvaluatorValue(booleanEvaluator.getValue(), booleanEvaluator.getClass(), result);
                }
                result.append(Strings.WHITE_SPACE);
            } else if(evaluator instanceof FieldEvaluator) {
                result.append(separator);
                FieldEvaluator fieldEvaluator = (FieldEvaluator) evaluator;
                if(fieldEvaluator.isTrueForced()) {
                    result.append(Boolean.TRUE.toString());
                } else {
                    if (fieldEvaluator.getLeftValue() == null) {
                        result.append(SystemProperties.get(SystemProperties.Query.ReservedWord.NULL));
                    } else {
                        result = toStringFieldEvaluatorValue(fieldEvaluator.getLeftValue(), fieldEvaluator.getLeftValue().getClass(), result);
                    }
                    result.append(Strings.WHITE_SPACE);
                    if (fieldEvaluator instanceof Distinct) {
                        result.append(SystemProperties.get(SystemProperties.Query.ReservedWord.DISTINCT)).append(Strings.WHITE_SPACE);
                    } else if (fieldEvaluator instanceof Equals) {
                        result.append(SystemProperties.get(SystemProperties.Query.ReservedWord.EQUALS)).append(Strings.WHITE_SPACE);
                    } else if (fieldEvaluator instanceof GreaterThanOrEqual) {
                        result.append(SystemProperties.get(SystemProperties.Query.ReservedWord.GREATER_THAN_OR_EQUALS)).append(Strings.WHITE_SPACE);
                    } else if (fieldEvaluator instanceof GreaterThan) {
                        result.append(SystemProperties.get(SystemProperties.Query.ReservedWord.GREATER_THAN)).append(Strings.WHITE_SPACE);
                    } else if (fieldEvaluator instanceof NotIn) {
                        result.append(SystemProperties.get(SystemProperties.Query.ReservedWord.NOT_IN)).append(Strings.WHITE_SPACE);
                    } else if (fieldEvaluator instanceof In) {
                        result.append(SystemProperties.get(SystemProperties.Query.ReservedWord.IN)).append(Strings.WHITE_SPACE);
                    } else if (fieldEvaluator instanceof Like) {
                        result.append(SystemProperties.get(SystemProperties.Query.ReservedWord.LIKE)).append(Strings.WHITE_SPACE);
                    } else if (fieldEvaluator instanceof SmallerThanOrEqual) {
                        result.append(SystemProperties.get(SystemProperties.Query.ReservedWord.SMALLER_THAN_OR_EQUALS)).append(Strings.WHITE_SPACE);
                    } else if (fieldEvaluator instanceof SmallerThan) {
                        result.append(SystemProperties.get(SystemProperties.Query.ReservedWord.SMALLER_THAN)).append(Strings.WHITE_SPACE);
                    }
                    if (fieldEvaluator.getRightValue() == null) {
                        result.append(SystemProperties.get(SystemProperties.Query.ReservedWord.NULL));
                    } else {
                        result = toStringFieldEvaluatorValue(fieldEvaluator.getRightValue(), fieldEvaluator.getRightValue().getClass(), result);
                    }
                }
                result.append(Strings.WHITE_SPACE);
            }
            separator = separatorValue + Strings.WHITE_SPACE;
        }
    }

    /**
     * Creates the string representation of the field evaluator.
     * @param value Object to create the string representation.
     * @param type Object type.
     * @param result Buffer with the current result.
     * @return String representation of the field evaluator.
     */
    private static Strings.Builder toStringFieldEvaluatorValue(Object value, Class type, Strings.Builder result) {
        if(FieldEvaluator.ReplaceableValue.class.isAssignableFrom(type)) {
            result.append(SystemProperties.get(SystemProperties.Query.ReservedWord.REPLACEABLE_VALUE));
        } else if(FieldEvaluator.QueryValue.class.isAssignableFrom(type)) {
            result.append(Strings.START_GROUP);
            result.append(((FieldEvaluator.QueryValue)value).getQuery().toString());
            result.append(Strings.END_GROUP);
        } else if(String.class.isAssignableFrom(type)) {
            result.append(SystemProperties.get(SystemProperties.Query.ReservedWord.STRING_DELIMITER));
            result.append(value);
            result.append(SystemProperties.get(SystemProperties.Query.ReservedWord.STRING_DELIMITER));
        } else if(Date.class.isAssignableFrom(type)) {
            result.append(SystemProperties.get(SystemProperties.Query.ReservedWord.STRING_DELIMITER));
            result.append(SystemProperties.getDateFormat(SystemProperties.Query.DATE_FORMAT).format((Date)value));
            result.append(SystemProperties.get(SystemProperties.Query.ReservedWord.STRING_DELIMITER));
        } else if(Collection.class.isAssignableFrom(type)) {
            result.append(Strings.START_GROUP);
            String separator = Strings.EMPTY_STRING;
            for(Object object : (Collection)value) {
                if(object != null) {
                    result.append(separator);
                    result = toStringFieldEvaluatorValue(object, object.getClass(), result);
                    separator = SystemProperties.get(SystemProperties.Query.ReservedWord.ARGUMENT_SEPARATOR);
                }
            }
            result.append(Strings.END_GROUP);
        } else {
            result.append(value.toString());
        }
        return result;
    }

    /**
     * This method is a particular implementation to create a bson document from a query instance.
     * @return Returns a bson document.
     */
    @Override
    public BsonDocument toBson() {
        BsonDocument document = new BsonDocument();
        document.put(PARCELABLE_CLASS_NAME, getClass().getName());
        document.put(QUERY_BSON_FIELD_NAME, toString());
        return document;
    }

    /**
     * This particular implementation do nothing to populate the instance.
     * @param document Bson document to populate the parcelable.
     * @param <P> Expected bson parcelable type.
     * @return Returns the same instance.
     */
    @Override
    public <P extends BsonParcelable> P populate(BsonDocument document) {
        return (P) this;
    }

    /**
     * Evaluates the query using a readable data source.
     * @param query Query to evaluate.
     * @return Collections of joinable map instances.
     */
    public static Collection<JoinableMap> evaluate(String query) {
        return evaluate(compile(query));
    }

    /**
     * Evaluates the query using a readable data source.
     * @param queryable Query to evaluate.
     * @return Collections of joinable map instances.
     */
    public static Collection<JoinableMap> evaluate(Queryable queryable) {
        return queryable.evaluate(new Queryable.ReadableDataSource());
    }

    /**
     * This method evaluate if the uuid instance is a uuid type 5 and contains
     * some name of the registered resource and invoke the read method of the resource.
     * @param uuid Resource id.
     * @param <O> Expected data type.
     * @return Resource instance.
     */
    public static <O extends Object> O evaluate(UUID uuid) {
        String resourceName = NamedUuid.getName(uuid);
        IdentifiableLayerInterface identifiableLayerInterface = Layers.get(IdentifiableLayerInterface.class, resourceName);
        return (O) identifiableLayerInterface.read(uuid);
    }

    /**
     * Create a query instance from sql definition.
     * @param sql Sql definition.
     * @return Query instance.
     */
    public static Query compile(String sql) {
        List<String> richTexts = Strings.groupRichText(sql);
        List<String> groups = Strings.replaceableGroup(Strings.removeLines(richTexts.get(richTexts.size() -1)));
        return compile(groups, richTexts, groups.size() -1);
    }

    /**
     * Create a query instance from sql definition.
     * @param groups
     * @param startGroup
     * @return Query instance.
     */
    private static Query compile(List<String> groups, List<String> richTexts, Integer startGroup) {
        Query query;
        Pattern pattern = SystemProperties.getPattern(SystemProperties.Query.SELECT_REGULAR_EXPRESSION);
        Matcher matcher = pattern.matcher(groups.get(startGroup));

        if(matcher.matches()) {
            String selectBody = matcher.group(SystemProperties.getInteger(SystemProperties.Query.SELECT_GROUP_INDEX));
            selectBody = selectBody.replaceFirst(Strings.CASE_INSENSITIVE_REGEX_FLAG+SystemProperties.get(SystemProperties.Query.ReservedWord.SELECT), Strings.EMPTY_STRING);
            String fromBody = matcher.group(SystemProperties.getInteger(SystemProperties.Query.FROM_GROUP_INDEX));
            fromBody = fromBody.replaceFirst(Strings.CASE_INSENSITIVE_REGEX_FLAG+SystemProperties.get(SystemProperties.Query.ReservedWord.FROM), Strings.EMPTY_STRING);
            String conditionalBody = matcher.group(SystemProperties.getInteger(SystemProperties.Query.CONDITIONAL_GROUP_INDEX));
            if(conditionalBody != null && conditionalBody.endsWith(SystemProperties.get(SystemProperties.Query.ReservedWord.STATEMENT_END))) {
                conditionalBody = conditionalBody.substring(0, conditionalBody.indexOf(SystemProperties.get(SystemProperties.Query.ReservedWord.STATEMENT_END))-1);
            }

            query = new Query(fromBody.trim());

            for(String returnField : selectBody.split(SystemProperties.get(
                    SystemProperties.Query.ReservedWord.ARGUMENT_SEPARATOR))) {
                query.addReturnField((QueryReturnParameter)
                        processStringValue(groups, richTexts, returnField, null, QueryReturnParameter.class, new ArrayList<>()));
            }

            if(conditionalBody != null) {
                Pattern conditionalPatter = SystemProperties.getPattern(SystemProperties.Query.CONDITIONAL_REGULAR_EXPRESSION, Pattern.CASE_INSENSITIVE);
                String[] conditionalElements = conditionalPatter.split(conditionalBody);
                String element;
                String elementValue;
                for (int i = 0; i < conditionalElements.length; i++) {
                    element = conditionalElements[i++].trim();
                    elementValue = conditionalElements[i].trim();
                    if (element.equalsIgnoreCase(SystemProperties.get(SystemProperties.Query.ReservedWord.JOIN)) ||
                            element.equalsIgnoreCase(SystemProperties.get(SystemProperties.Query.ReservedWord.INNER)) ||
                            element.equalsIgnoreCase(SystemProperties.get(SystemProperties.Query.ReservedWord.LEFT)) ||
                            element.equalsIgnoreCase(SystemProperties.get(SystemProperties.Query.ReservedWord.RIGHT))) {

                        Join.JoinType type = Join.JoinType.valueOf(element.toUpperCase());
                        if(type != Join.JoinType.JOIN) {
                            elementValue = conditionalElements[++i].trim();
                        }

                        String[] joinElements = elementValue.split(SystemProperties.get(SystemProperties.Query.JOIN_REGULAR_EXPRESSION));
                        String joinResource = joinElements[SystemProperties.getInteger(SystemProperties.Query.JOIN_RESOURCE_NAME_INDEX)].trim();
                        String joinEvaluators = joinElements[SystemProperties.getInteger(SystemProperties.Query.JOIN_EVALUATORS_INDEX)].trim();
                        if(joinEvaluators.startsWith(Strings.REPLACEABLE_GROUP)) {
                            joinEvaluators = groups.get(Integer.parseInt(joinEvaluators.replace(Strings.REPLACEABLE_GROUP, Strings.EMPTY_STRING)));
                        }

                        Join join = new Join(query, joinResource, type);
                        completeEvaluatorCollection(joinEvaluators, groups, richTexts, join, 0, new AtomicInteger(0));
                        query.addJoin(join);
                    } else if (element.equalsIgnoreCase(SystemProperties.get(SystemProperties.Query.ReservedWord.WHERE))) {
                        completeEvaluatorCollection(elementValue, groups, richTexts, query, 0, new AtomicInteger(0));
                    } else if (element.equalsIgnoreCase(SystemProperties.get(SystemProperties.Query.ReservedWord.ORDER_BY))) {
                        for (String orderField : elementValue.split(SystemProperties.get(
                                SystemProperties.Query.ReservedWord.ARGUMENT_SEPARATOR))) {
                            query.addOrderField((QueryOrderParameter)
                                    processStringValue(groups, richTexts, orderField, null, QueryOrderParameter.class, new ArrayList<>()));
                        }
                    } else if (element.equalsIgnoreCase(SystemProperties.get(SystemProperties.Query.ReservedWord.GROUP_BY))) {
                        for (String orderField : elementValue.split(SystemProperties.get(
                                SystemProperties.Query.ReservedWord.ARGUMENT_SEPARATOR))) {
                            query.addGroupField((QueryReturnParameter)
                                    processStringValue(groups, richTexts, orderField, null, QueryReturnParameter.class, new ArrayList<>()));
                        }
                    } else if (element.equalsIgnoreCase(SystemProperties.get(SystemProperties.Query.ReservedWord.LIMIT))) {
                        query.setLimit(Integer.parseInt(elementValue));
                    } else if (element.equalsIgnoreCase(SystemProperties.get(SystemProperties.Query.ReservedWord.START))) {
                        query.setStart(Integer.parseInt(elementValue));
                    }
                }
            }
        } else {
            MatchResult mr = matcher.toMatchResult();
            throw new IllegalArgumentException();
        }

        return query;
    }

    /**
     * Complete the evaluator collections with all the evaluator definitions in the groups.
     * @param groups Where groups.
     * @param parentCollection Parent collection.
     * @param definitionIndex Definition index into the groups.
     */
    private static final void completeEvaluatorCollection(String startElement, List<String> groups, List<String> richTexts,
                                                          EvaluatorCollection parentCollection,
                                                          Integer definitionIndex,
                                                          AtomicInteger placesIndex) {
        Pattern wherePatter = SystemProperties.getPattern(SystemProperties.Query.EVALUATOR_COLLECTION_REGULAR_EXPRESSION, Pattern.CASE_INSENSITIVE);
        String[] evaluatorDefinitions;
        if(startElement != null) {
            evaluatorDefinitions = wherePatter.split(startElement);
        } else {
            evaluatorDefinitions = wherePatter.split(groups.get(definitionIndex));
        }
        EvaluatorCollection collection = null;
        List<String> pendingDefinitions = new ArrayList<>();
        for(String definition : evaluatorDefinitions) {
            definition = definition.trim();
            if (definition.equalsIgnoreCase(SystemProperties.get(SystemProperties.Query.ReservedWord.AND))) {
                if (collection == null) {
                    if(parentCollection instanceof Query || parentCollection instanceof Join || parentCollection instanceof And) {
                        collection = parentCollection;
                    } else {
                        collection = parentCollection.and();
                    }
                } else if (collection instanceof Or) {
                    if(parentCollection instanceof Query || parentCollection instanceof Join || parentCollection instanceof And) {
                        collection = parentCollection;
                    } else {
                        collection = parentCollection.and();
                    }
                }
            } else if (definition.equalsIgnoreCase(SystemProperties.get(SystemProperties.Query.ReservedWord.OR))) {
                if (collection == null) {
                    if(parentCollection instanceof Or) {
                        collection = parentCollection;
                    } else {
                        collection = parentCollection.or();
                    }
                } else if(collection instanceof Query || collection instanceof Join || collection instanceof And) {
                    if(parentCollection instanceof Or) {
                        collection = parentCollection;
                    } else {
                        collection = parentCollection.or();
                    }
                }
            } else {
                pendingDefinitions.add(definition);
                if(collection != null) {
                    for(String pendingDefinition : pendingDefinitions) {
                        processDefinition(pendingDefinition, collection, groups, richTexts, placesIndex);
                    }
                    pendingDefinitions.clear();
                } else if(pendingDefinitions.size() > 1) {
                    throw new IllegalArgumentException("");
                }
            }
        }

        for(String pendingDefinition : pendingDefinitions) {
            if(collection != null) {
                processDefinition(pendingDefinition, collection, groups, richTexts, placesIndex);
            } else {
                processDefinition(pendingDefinition, parentCollection, groups, richTexts, placesIndex);
            }
        }
    }

    /**
     * Creates a conditional evaluator from string representation.
     * @param definition String definition of the conditional.
     * @param collection Evaluator collection to put the conditional processed.
     * @param groups Sub representation of the main representation.
     * @param placesIndex Place counter of the group list.
     */
    private static void processDefinition(String definition, EvaluatorCollection collection, List<String> groups, List<String> richTexts, AtomicInteger placesIndex) {
        String[] evaluatorValues;
        Object leftValue;
        Object rightValue;
        String firstArgument;
        String secondArgument;
        String operator;
        Evaluator evaluator;

        if (definition.startsWith(Strings.REPLACEABLE_GROUP)) {
            Integer index = Integer.parseInt(definition.replace(Strings.REPLACEABLE_GROUP, Strings.EMPTY_STRING));
            completeEvaluatorCollection(null, groups, richTexts, collection, index, placesIndex);
        } else {
            evaluatorValues = definition.split(SystemProperties.get(SystemProperties.Query.OPERATION_REGULAR_EXPRESSION));
            boolean operatorDone = false;
            firstArgument = Strings.EMPTY_STRING;
            secondArgument = Strings.EMPTY_STRING;
            operator = Strings.EMPTY_STRING;
            for (String evaluatorValue : evaluatorValues) {
                evaluatorValue = evaluatorValue.trim();
                if (evaluatorValue.equalsIgnoreCase(SystemProperties.get(SystemProperties.Query.ReservedWord.NOT))) {
                    operator += evaluatorValue + Strings.WHITE_SPACE;
                } else if (evaluatorValue.equalsIgnoreCase(SystemProperties.get(SystemProperties.Query.ReservedWord.NOT_2))) {
                    operator += evaluatorValue;
                } else if (evaluatorValue.equalsIgnoreCase(SystemProperties.get(SystemProperties.Query.ReservedWord.DISTINCT))
                        || evaluatorValue.equalsIgnoreCase(SystemProperties.get(SystemProperties.Query.ReservedWord.DISTINCT_2))
                        || evaluatorValue.equalsIgnoreCase(SystemProperties.get(SystemProperties.Query.ReservedWord.EQUALS))
                        || evaluatorValue.equalsIgnoreCase(SystemProperties.get(SystemProperties.Query.ReservedWord.GREATER_THAN))
                        || evaluatorValue.equalsIgnoreCase(SystemProperties.get(SystemProperties.Query.ReservedWord.GREATER_THAN_OR_EQUALS))
                        || evaluatorValue.equalsIgnoreCase(SystemProperties.get(SystemProperties.Query.ReservedWord.IN))
                        || evaluatorValue.equalsIgnoreCase(SystemProperties.get(SystemProperties.Query.ReservedWord.LIKE))
                        || evaluatorValue.equalsIgnoreCase(SystemProperties.get(SystemProperties.Query.ReservedWord.SMALLER_THAN))
                        || evaluatorValue.equalsIgnoreCase(SystemProperties.get(SystemProperties.Query.ReservedWord.SMALLER_THAN_OR_EQUALS))) {
                    operator += evaluatorValue;
                    operatorDone = true;
                } else if (operatorDone) {
                    secondArgument += evaluatorValue + Strings.WHITE_SPACE;
                } else {
                    firstArgument += evaluatorValue + Strings.WHITE_SPACE;
                }
            }

            List<QueryField> presentFields = new ArrayList<>();
            if (operator == null || operator.trim().isEmpty()) {
                leftValue = processStringValue(groups, richTexts, firstArgument.trim(), placesIndex, QueryParameter.class, presentFields);
                evaluator = new BooleanEvaluator(leftValue);
            } else {
                leftValue = processStringValue(groups, richTexts, firstArgument.trim(), placesIndex, QueryParameter.class, presentFields);
                if (leftValue instanceof String) {
                    leftValue = Strings.reverseGrouping((String) leftValue, groups);
                }
                rightValue = processStringValue(groups, richTexts, secondArgument.trim(), placesIndex, QueryParameter.class, presentFields);
                if (rightValue instanceof String) {
                    rightValue = Strings.reverseGrouping((String) rightValue, groups);
                }
                operator = operator.trim();


                if (operator.equalsIgnoreCase(SystemProperties.get(SystemProperties.Query.ReservedWord.DISTINCT)) ||
                        operator.equalsIgnoreCase(SystemProperties.get(SystemProperties.Query.ReservedWord.DISTINCT_2))) {
                    evaluator = new Distinct(leftValue, rightValue);
                } else if (operator.equalsIgnoreCase(SystemProperties.get(SystemProperties.Query.ReservedWord.EQUALS))) {
                    evaluator = new Equals(leftValue, rightValue);
                } else if (operator.equalsIgnoreCase(SystemProperties.get(SystemProperties.Query.ReservedWord.GREATER_THAN))) {
                    evaluator = new GreaterThan(leftValue, rightValue);
                } else if (operator.equalsIgnoreCase(SystemProperties.get(SystemProperties.Query.ReservedWord.GREATER_THAN_OR_EQUALS))) {
                    evaluator = new GreaterThanOrEqual(leftValue, rightValue);
                } else if (operator.equalsIgnoreCase(SystemProperties.get(SystemProperties.Query.ReservedWord.IN))) {
                    evaluator = new In(leftValue, rightValue);
                } else if (operator.equalsIgnoreCase(SystemProperties.get(SystemProperties.Query.ReservedWord.LIKE))) {
                    evaluator = new Like(leftValue, rightValue);
                } else if (operator.equalsIgnoreCase(SystemProperties.get(SystemProperties.Query.ReservedWord.NOT_IN))) {
                    evaluator = new NotIn(leftValue, rightValue);
                } else if (operator.equalsIgnoreCase(SystemProperties.get(SystemProperties.Query.ReservedWord.SMALLER_THAN))) {
                    evaluator = new SmallerThan(leftValue, rightValue);
                } else if (operator.equalsIgnoreCase(SystemProperties.get(SystemProperties.Query.ReservedWord.SMALLER_THAN_OR_EQUALS))) {
                    evaluator = new SmallerThanOrEqual(leftValue, rightValue);
                } else {
                    throw new IllegalArgumentException("Unsupported operator '" + operator + "'");
                }
            }

            if(evaluator instanceof BaseEvaluator) {
                ((BaseEvaluator)evaluator).setEvaluatorFields(presentFields);
            }

            collection.addEvaluator(evaluator);
        }
    }

    /**
     * Process the string representation to obtain the specific object type.
     * @param groups Sub representation of the main representation.
     * @param stringValue String representation to process.
     * @param placesIndex Place counter of the group list.
     * @param parameterClass Parameter class.
     * @return Return the specific implementation of the string representation.
     */
    private static Object processStringValue(List<String> groups, List<String> richTexts, String stringValue, AtomicInteger placesIndex, Class parameterClass, List<QueryField> presentFields) {
        Object result = null;
        String trimmedStringValue = stringValue.trim();
        if(trimmedStringValue.equals(SystemProperties.get(SystemProperties.Query.ReservedWord.REPLACEABLE_VALUE))) {
            //If the string value is equals than "?" then the value object is an instance of ReplaceableValue.
            result = new FieldEvaluator.ReplaceableValue(placesIndex.getAndAdd(1));
        } else if(trimmedStringValue.equalsIgnoreCase(SystemProperties.get(SystemProperties.Query.ReservedWord.NULL))) {
            result = null;
        } else if(trimmedStringValue.equalsIgnoreCase(SystemProperties.get(SystemProperties.Query.ReservedWord.TRUE))) {
            result = true;
        } else if(trimmedStringValue.equalsIgnoreCase(SystemProperties.get(SystemProperties.Query.ReservedWord.FALSE))) {
            result = false;
        } else if(trimmedStringValue.startsWith(SystemProperties.get(SystemProperties.Query.ReservedWord.STRING_DELIMITER))) {
            if (trimmedStringValue.endsWith(SystemProperties.get(SystemProperties.Query.ReservedWord.STRING_DELIMITER))) {
                //If the string value start and end with "'" then the value can be a string or a date object.
                trimmedStringValue = trimmedStringValue.substring(1, trimmedStringValue.length() - 1);
                trimmedStringValue = richTexts.get(Integer.parseInt(trimmedStringValue.replace(Strings.REPLACEABLE_RICH_TEXT, Strings.EMPTY_STRING)));

                //Clean the value to remove all the skip characters into the string value.
                trimmedStringValue = trimmedStringValue.replace(Strings.RICH_TEXT_SKIP_CHARACTER + Strings.RICH_TEXT_SEPARATOR, Strings.RICH_TEXT_SEPARATOR);

                try {
                    result = SystemProperties.getDateFormat(SystemProperties.Query.DATE_FORMAT).parse(trimmedStringValue);
                } catch (Exception ex) {
                    //The value is not a date then the value is a string
                    while(trimmedStringValue.contains(Strings.REPLACEABLE_GROUP)) {
                        trimmedStringValue = Strings.reverseGrouping(trimmedStringValue, groups);
                    }
                    result = trimmedStringValue;
                }
            } else {
                throw new IllegalArgumentException("");
            }
        } else if(trimmedStringValue.startsWith(Strings.REPLACEABLE_GROUP)) {
            Integer index = Integer.parseInt(trimmedStringValue.replace(Strings.REPLACEABLE_GROUP, Strings.EMPTY_STRING));
            String group = groups.get(index);
            if(group.toUpperCase().startsWith(SystemProperties.get(SystemProperties.Query.ReservedWord.SELECT))) {
                result = new FieldEvaluator.QueryValue(Query.compile(groups, richTexts, index));
            } else {
                //If the string value start with "(" and end with ")" then the value is a collection.
                Collection<Object> collection = new ArrayList<>();
                for (String subStringValue : group.split(SystemProperties.get(SystemProperties.Query.ReservedWord.ARGUMENT_SEPARATOR))) {
                    collection.add(processStringValue(groups, richTexts, subStringValue.trim(), placesIndex, parameterClass, presentFields));
                }
                result = collection;
            }
        } else if(trimmedStringValue.matches(SystemProperties.get(SystemProperties.HCJF_UUID_REGEX))) {
            result = UUID.fromString(trimmedStringValue);
        } else if(trimmedStringValue.matches(SystemProperties.get(SystemProperties.HCJF_INTEGER_NUMBER_REGEX))) {
            result = Long.parseLong(trimmedStringValue);
        } else if(trimmedStringValue.matches(SystemProperties.get(SystemProperties.HCJF_DECIMAL_NUMBER_REGEX))) {
            try {
                result = SystemProperties.getDecimalFormat(SystemProperties.Query.DECIMAL_FORMAT).parse(trimmedStringValue);
            } catch (ParseException e) {
                throw new IllegalArgumentException("Unable to parse decimal number");
            }
        } else if(trimmedStringValue.matches(SystemProperties.get(SystemProperties.HCJF_SCIENTIFIC_NUMBER_REGEX))) {
            try {
                result = SystemProperties.getDecimalFormat(SystemProperties.Query.SCIENTIFIC_NOTATION_FORMAT).parse(trimmedStringValue);
            } catch (ParseException e) {
                throw new IllegalArgumentException("Unable to parse scientific number");
            }
        } else if(trimmedStringValue.matches(SystemProperties.get(SystemProperties.HCJF_MATH_CONNECTOR_REGULAR_EXPRESSION)) &&
                trimmedStringValue.matches(SystemProperties.get(SystemProperties.HCJF_MATH_REGULAR_EXPRESSION))) {
            String alias = null;
            String[] asParts = trimmedStringValue.split(SystemProperties.get(SystemProperties.Query.AS_REGULAR_EXPRESSION));
            if(asParts.length == 3) {
                trimmedStringValue = asParts[0].trim();
                alias = asParts[2].trim();
            }

            //If the string matchs with a math expression then creates a function that resolves this math expression.
            String[] mathExpressionParts = trimmedStringValue.split(SystemProperties.get(SystemProperties.HCJF_MATH_SPLITTER_REGULAR_EXPRESSION));
            List<Object> parameters = new ArrayList<>();
            String currentValue;
            boolean desc = false;
            for (int i = 0; i < mathExpressionParts.length; i++) {
                currentValue = mathExpressionParts[i];
                if(i == mathExpressionParts.length - 1){
                    //This code run only one time for the last part.
                    if(parameterClass.equals(QueryReturnParameter.class)) {
                        //Check if the last part contains the 'AS' word
                        String[] parts = currentValue.split(SystemProperties.get(SystemProperties.Query.AS_REGULAR_EXPRESSION));
                        if (parts.length == 2) {
                            currentValue = parts[0].trim();
                            alias = parts[1].trim();
                        }
                    } else if(parameterClass.equals(QueryOrderParameter.class)) {
                        //Check if the last part contains the 'DESC' word
                        String[] parts = currentValue.split(SystemProperties.get(SystemProperties.Query.DESC_REGULAR_EXPRESSION));
                        if(parts.length == 3) {
                            currentValue = parts[0].trim();
                            if(parts[2].trim().equalsIgnoreCase(SystemProperties.get(SystemProperties.Query.ReservedWord.DESC))) {
                                desc = true;
                            }
                        }
                    }
                }

                if(currentValue.matches(SystemProperties.get(SystemProperties.HCJF_MATH_CONNECTOR_REGULAR_EXPRESSION))) {
                    //If the current value is a math connector (+-*/) the this connector is a function parameter.
                    parameters.add(currentValue.trim());
                } else {
                    //If the current value is not a math connector then this string is evaluated recursively.
                    parameters.add(processStringValue(groups, richTexts, currentValue, placesIndex, QueryParameter.class, presentFields));
                }
            }

            if(parameterClass.equals(QueryParameter.class)) {
                result = new QueryFunction(Strings.reverseGrouping(trimmedStringValue, groups),
                        SystemProperties.get(SystemProperties.Query.Function.MATH_EVAL_EXPRESSION_NAME), parameters);
            } else if(parameterClass.equals(QueryReturnParameter.class)) {
                result = new QueryReturnFunction(Strings.reverseGrouping(trimmedStringValue, groups),
                        SystemProperties.get(SystemProperties.Query.Function.MATH_EVAL_EXPRESSION_NAME), parameters, alias);
            } else if(parameterClass.equals(QueryOrderParameter.class)) {
                result = new QueryOrderFunction(Strings.reverseGrouping(trimmedStringValue, groups),
                        SystemProperties.get(SystemProperties.Query.Function.MATH_EVAL_EXPRESSION_NAME), parameters, desc);
            }
        } else {
            //Default case, only must be a query parameter.
            String functionName = null;
            String originalValue = null;
            String replaceValue = null;
            String group = null;
            List<Object> functionParameters = null;
            Boolean function = false;
            if(trimmedStringValue.contains(Strings.REPLACEABLE_GROUP)) {
                //If the string contains a replaceable group character then the parameter is a function.
                replaceValue = Strings.getGroupIndex(trimmedStringValue);
                group = groups.get(Integer.parseInt(replaceValue.replace(Strings.REPLACEABLE_GROUP,Strings.EMPTY_STRING)));
                functionName = trimmedStringValue.substring(0, trimmedStringValue.indexOf(Strings.REPLACEABLE_GROUP));
                originalValue = trimmedStringValue.replace(replaceValue, Strings.START_GROUP + group + Strings.END_GROUP);
                functionParameters = new ArrayList<>();
                for(String param : group.split(SystemProperties.get(SystemProperties.Query.ReservedWord.ARGUMENT_SEPARATOR))) {
                    functionParameters.add(processStringValue(groups, richTexts, param, placesIndex, parameterClass, presentFields));
                }
                function = true;
            } else {
                originalValue = trimmedStringValue;
            }

            if(parameterClass.equals(QueryParameter.class)) {
                //If the parameter class is the default class then the result will be a
                //QueryFunction.class instance or QueryField.class instance.
                if(function) {
                    result = new QueryFunction(originalValue, functionName, functionParameters);
                } else {
                    result = new QueryField(trimmedStringValue);
                }
            } else if(parameterClass.equals(QueryReturnParameter.class)) {
                //If the parameter class is the QueryReturnParameter.class then the result will be a
                //QueryReturnFunction.class instance or QueryReturnField.class instance.
                String alias = null;
                String[] parts = originalValue.split(SystemProperties.get(SystemProperties.Query.AS_REGULAR_EXPRESSION));
                if(parts.length == 3) {
                    originalValue = parts[0].trim();
                    alias = parts[2].trim();
                }

                if(function) {
                    result = new QueryReturnFunction(originalValue, functionName, functionParameters, alias);
                } else {
                    result = new QueryReturnField(originalValue, alias);
                }
            } else if(parameterClass.equals(QueryOrderParameter.class)) {
                //If the parameter class is the QueryOrderParameter.class then the result will be a
                //QueryOrderFunction.class instance or QueryOrderField.class instance.
                boolean desc = false;
                String[] parts = originalValue.split(SystemProperties.get(SystemProperties.Query.DESC_REGULAR_EXPRESSION));
                if(parts.length == 2) {
                    originalValue = parts[0].trim();
                    if(parts[1].trim().equalsIgnoreCase(SystemProperties.get(SystemProperties.Query.ReservedWord.DESC))) {
                        desc = true;
                    }
                }

                if(function) {
                    result = new QueryOrderFunction(originalValue, functionName, functionParameters, desc) ;
                } else {
                    result = new QueryOrderField(originalValue, desc);
                }
            }
        }

        if(result instanceof QueryField) {
            presentFields.add((QueryField) result);
        }

        return result;
    }

    @Override
    public boolean equals(Object obj) {
        return (obj instanceof Query) && obj.toString().equals(toString());
    }

    /**
     * Represents an query id. Wrapper of the UUID class.
     */
    public static final class QueryId {

        private final UUID id;

        private QueryId() {
            this.id = UUID.randomUUID();
        }

        public QueryId(UUID id) {
            this.id = id;
        }

        /**
         * Get the UUID instance.
         * @return UUID instance.
         */
        public UUID getId() {
            return id;
        }
    }

    /**
     * Group all the query components.
     */
    public interface QueryComponent {}

    /**
     * Represents any kind of resource.
     */
    public static class QueryResource implements Comparable<QueryResource>, QueryComponent {

        private String resourceName;

        public QueryResource(String resourceName) {
            this.resourceName = resourceName;
        }

        /**
         * Set the name of the resource.
         * @param resourceName Name of the resource
         */
        public void setResourceName(String resourceName) {
            this.resourceName = resourceName;
        }

        /**
         * Return the resource name.
         * @return Resource name.
         */
        public String getResourceName() {
            return resourceName;
        }

        @Override
        public boolean equals(Object obj) {
            boolean result = false;
            if(obj instanceof QueryResource) {
                result = resourceName.equals(((QueryResource)obj).getResourceName());
            }
            return result;
        }

        @Override
        public int compareTo(QueryResource o) {
            return resourceName.compareTo(o.getResourceName());
        }

        @Override
        public String toString() {
            return getResourceName();
        }
    }

    public static abstract class QueryParameter implements Comparable<QueryParameter>, QueryComponent {

        private final String originalValue;

        public QueryParameter(String originalValue) {
            this.originalValue = originalValue.trim();
        }

        /**
         * Return the original representation of the field.
         * @return Original representation.
         */
        @Override
        public String toString() {
            return originalValue;
        }

        /**
         * Compare the original value of the fields.
         * @param obj Other field.
         * @return True if the fields are equals.
         */
        @Override
        public boolean equals(Object obj) {
            return toString().equals(obj.toString());
        }

        /**
         * Compare the string representation of both objects.
         * @param o Other object.
         * @return Magnitude of the difference between both objects.
         */
        @Override
        public int compareTo(QueryParameter o) {
            return toString().compareTo(o.toString());
        }

    }

    /**
     *
     */
    public static class QueryFunction extends QueryParameter {

        private final String functionName;
        private final List<Object> parameters;

        public QueryFunction(String originalFunction, String functionName, List<Object> parameters) {
            super(originalFunction);
            this.functionName = functionName;
            this.parameters = parameters;
        }

        public String getFunctionName() {
            return functionName;
        }

        public List<Object> getParameters() {
            return parameters;
        }

        public Set<QueryResource> getResources() {
            Set<QueryResource> queryResources = new TreeSet<>();

            for(Object parameter : parameters) {
                if(parameter instanceof QueryField) {
                    queryResources.add(((QueryField)parameter).getResource());
                } else if(parameter instanceof QueryFunction) {
                    queryResources.addAll(((QueryFunction)parameter).getResources());
                }
            }

            return queryResources;
        }
    }

    /**
     * This class represents any kind of query fields.
     */
    public static class QueryField extends QueryParameter {

        private QueryResource resource;
        private String fieldName;
        private List<QueryField> path;
        private final String completeFieldName;
        private final String index;

        public QueryField(String field) {
            super(field);

            if(field.contains(Strings.CLASS_SEPARATOR)) {
                resource = new QueryResource(field.substring(0, field.lastIndexOf(Strings.CLASS_SEPARATOR)));
                this.fieldName = field.substring(field.lastIndexOf(Strings.CLASS_SEPARATOR) + 1).trim();
            } else {
                resource = null;
                this.fieldName = field.trim();
            }

            if(fieldName.contains(Strings.START_SUB_GROUP)) {
                index = fieldName.substring(field.indexOf(Strings.START_SUB_GROUP) + 1, field.indexOf(Strings.END_SUB_GROUP)).trim();
                fieldName = fieldName.substring(0, field.indexOf(Strings.START_SUB_GROUP)).trim();
            } else {
                index = null;
            }

            completeFieldName = (resource == null ? "" : resource + Strings.CLASS_SEPARATOR) + fieldName;

            path = new ArrayList<>();
            if(field.contains(Strings.CLASS_SEPARATOR)) {
                for (String pathPart : field.split(Strings.CLASS_SEPARATOR)) {
                    path.add(new QueryField(pathPart));
                }
            } else {
                path.add(this);
            }
        }

        /**
         * Return the resource of the field.
         * @param resource Field resource.
         */
        protected void setResource(QueryResource resource) {
            this.resource = resource;
        }

        /**
         * Return the resource associated to the field.
         * @return Resource name, can be null.
         */
        public QueryResource getResource() {
            return resource;
        }

        /**
         * Return the field name without associated resource or index.
         * @return Field name.
         */
        public String getFieldName() {
            return fieldName;
        }

        /**
         * Return the resource name and the field name into the same value.
         * @return Complete name.
         */
        public String getCompleteFieldName() {
            return completeFieldName;
        }

        /**
         * Return the index associated to the field.
         * @return Index, can be null.
         */
        public String getIndex() {
            return index;
        }

        /**
         * Returns the path of the field.
         * @return Path of the field.
         */
        public List<QueryField> getPath() {
            return path;
        }
    }

    public interface QueryReturnParameter extends QueryComponent {

        /**
         * Return the field alias, can be null.
         * @return Field alias.
         */
        String getAlias();

    }

    /**
     * This kind of component represent the fields to be returned into the query.
     */
    public static class QueryReturnField extends QueryField implements QueryReturnParameter {

        private final String alias;

        public QueryReturnField(String field) {
            this(field, null);
        }

        public QueryReturnField(String field, String alias) {
            super(field);
            this.alias = alias;
        }

        /**
         * Return the field alias, can be null.
         * @return Field alias.
         */
        public String getAlias() {
            return alias;
        }

    }

    public static class QueryReturnFunction extends QueryFunction implements QueryReturnParameter {

        private final String alias;
        private boolean aggregate;

        public QueryReturnFunction(String originalFunction, String functionName, List<Object> parameters, String alias) {
            super(originalFunction, functionName, parameters);

            aggregate = false;
            try {
                QueryAggregateFunctionLayerInterface aggregateFunctionLayerInterface =
                        Layers.get(QueryAggregateFunctionLayerInterface.class,
                                SystemProperties.get(SystemProperties.Query.Function.NAME_PREFIX) +functionName);
                aggregate = aggregateFunctionLayerInterface != null;
            } catch (Exception ex){}

            this.alias = alias;
        }

        /**
         * Return the field alias, can be null.
         * @return Field alias.
         */
        public String getAlias() {
            return alias;
        }

        /**
         * Verify if the function is an aggregate function or not.
         * @return True if the function is an aggregate function and false in the otherwise.
         */
        public boolean isAggregate() {
            return aggregate;
        }
    }

    public interface QueryOrderParameter extends QueryComponent {

        /**
         * Return the desc property.
         * @return Desc property.
         */
        boolean isDesc();

    }

    /**
     * This class represents a order field with desc property
     */
    public static class QueryOrderField extends QueryField implements QueryOrderParameter {

        private final boolean desc;

        public QueryOrderField(String field, boolean desc) {
            super(field);
            this.desc = desc;
        }

        /**
         * Return the desc property.
         * @return Desc property.
         */
        public boolean isDesc() {
            return desc;
        }
    }

    public static class QueryOrderFunction extends QueryFunction implements QueryOrderParameter {

        private final boolean desc;

        public QueryOrderFunction(String originalFunction, String functionName, List<Object> parameters, boolean desc) {
            super(originalFunction, functionName, parameters);
            this.desc = desc;
        }

        /**
         * Return the desc property.
         * @return Desc property.
         */
        public boolean isDesc() {
            return desc;
        }

    }

    /**
     * This inner class implements the custom method to create a query instance from a bson document.
     */
    public static class QueryBsonBuilderLayer extends Layer implements BsonCustomBuilderLayer<Query> {

        public QueryBsonBuilderLayer() {
            super(Query.class.getName());
        }

        /**
         * This implementation required that the document contains a field called '__query__' to create the query instance.
         * @param document Bson document.
         * @return Returns a query instance.
         */
        @Override
        public Query create(BsonDocument document) {
            return Query.compile(document.get(QUERY_BSON_FIELD_NAME).getAsString());
        }

    }

}
