/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.mitre.quaerite.core.serializers;

import static org.mitre.quaerite.core.serializers.FeatureFactorySerializer.VALUES_KEY;

import java.lang.reflect.Constructor;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.google.gson.JsonArray;
import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonPrimitive;
import com.google.gson.JsonSerializationContext;
import com.google.gson.JsonSerializer;
import org.apache.commons.math3.analysis.function.Sin;
import org.apache.log4j.Logger;
import org.mitre.quaerite.core.features.Boost;
import org.mitre.quaerite.core.features.Feature;
import org.mitre.quaerite.core.features.FloatFeature;
import org.mitre.quaerite.core.features.Fuzziness;
import org.mitre.quaerite.core.features.MultiMatchType;
import org.mitre.quaerite.core.features.NegativeBoost;
import org.mitre.quaerite.core.features.PF;
import org.mitre.quaerite.core.features.QF;
import org.mitre.quaerite.core.features.StringFeature;
import org.mitre.quaerite.core.features.StringListFeature;
import org.mitre.quaerite.core.features.TIE;
import org.mitre.quaerite.core.features.WeightableField;
import org.mitre.quaerite.core.features.WeightableListFeature;
import org.mitre.quaerite.core.queries.BooleanClause;
import org.mitre.quaerite.core.queries.BooleanQuery;
import org.mitre.quaerite.core.queries.BoostingQuery;
import org.mitre.quaerite.core.queries.DisMaxQuery;
import org.mitre.quaerite.core.queries.EDisMaxQuery;
import org.mitre.quaerite.core.queries.LuceneQuery;
import org.mitre.quaerite.core.queries.MultiFieldQuery;
import org.mitre.quaerite.core.queries.MultiMatchQuery;
import org.mitre.quaerite.core.queries.Query;
import org.mitre.quaerite.core.queries.QueryOperator;
import org.mitre.quaerite.core.queries.SingleStringQuery;
import org.mitre.quaerite.core.queries.TermQuery;
import org.mitre.quaerite.core.queries.TermsQuery;
import org.mitre.quaerite.core.util.JsonUtil;
import org.mitre.quaerite.core.util.MathUtil;

public class QuerySerializer extends AbstractFeatureSerializer
        implements JsonSerializer<Query>, JsonDeserializer<Query> {

    static Logger LOG = Logger.getLogger(QuerySerializer.class);

    private static final String DEFAULT_FIELD = "defaultField";
    private static final String FIELD = "field";
    private static final String QUERY_STRING = "queryString";
    private static final String QUERY_STRING_NAME = "queryStringName";
    private static final String QUERY_OPERATOR = "q.op";
    private static final String AND = "AND";
    private static final String OR = "OR";
    private static final String MULTI_MATCH = "multi_match";
    private static final String EDISMAX = "edismax";
    private static final String DISMAX = "dismax";
    private static final String LUCENE = "lucene";
    private static final String TERMS = "terms";
    private static final String TERM = "term";
    private static final String TIE = "tie";
    private static final String BOOL = "bool";
    private static final String BOOLEAN = "boolean";
    private static final String BOOSTING = "boosting";


    @Override
    public Query deserialize(JsonElement jsonElement, Type type,
                         JsonDeserializationContext jsonDeserializationContext) throws JsonParseException {
        JsonObject root = jsonElement.getAsJsonObject();
        String queryType = JsonUtil.getSingleChildName(root);
        return buildQuery(queryType, root.getAsJsonObject(queryType));
    }

    private Query buildQuery(String queryType, JsonObject jsonObj) {
        if (queryType.equals(EDISMAX)) {
            return buildEDisMax(jsonObj);
        } else if (queryType.equals(DISMAX)) {
            return buildDisMax(jsonObj);
        } else if (queryType.equals(LUCENE)) {
            String defaultField = (jsonObj.has(DEFAULT_FIELD)) ? jsonObj.get(DEFAULT_FIELD).getAsString() : "";
            String qOpString = (jsonObj.has(QUERY_OPERATOR))? jsonObj.get(QUERY_OPERATOR).getAsString() : null;
            String queryString = jsonObj.get(QUERY_STRING).getAsString();

            QueryOperator.OPERATOR qop = (qOpString == null) ? LuceneQuery.DEFAULT_QUERY_OPERATOR :
                    (qOpString.equalsIgnoreCase(AND) ? QueryOperator.OPERATOR.AND : QueryOperator.OPERATOR.OR);
            return new LuceneQuery(defaultField, queryString, qop);
        } else if (queryType.equals(TERMS)) { ;
            List<String> terms = toStringList(jsonObj.get(TERMS));
            String field = jsonObj.get(FIELD).getAsString();
            return new TermsQuery(field, terms);
        } else if (queryType.equals(TERM)) {
            String term = jsonObj.get(TERM).getAsString();
            String field = jsonObj.get(FIELD).getAsString();
            return new TermQuery(field, term);
        } else if (queryType.equals(MULTI_MATCH)) {
            return buildMultiMatch(jsonObj);
        } else if (queryType.equals(BOOL) || queryType.equals(BOOLEAN)) {
            return buildBoolean(jsonObj);
        } else if (queryType.equals(BOOSTING)) {
            return buildBoosting(jsonObj);
        } else {
            throw new IllegalArgumentException("I regret I don't yet support: "+queryType);
        }

    }

    private Query buildBoosting(JsonObject obj) {
        SingleStringQuery positiveQuery;
        SingleStringQuery negativeQuery;

        JsonObject positiveObj = obj.getAsJsonObject("positive");
        String posQueryType = JsonUtil.getSingleChildName(positiveObj);
        positiveQuery = (SingleStringQuery) buildQuery(posQueryType, positiveObj.getAsJsonObject(posQueryType));

        JsonObject negObj = obj.getAsJsonObject("negative");
        String negQueryType = JsonUtil.getSingleChildName(negObj);
        negativeQuery = (SingleStringQuery) buildQuery(negQueryType, negObj.getAsJsonObject(negQueryType));

        NegativeBoost negativeBoost = new NegativeBoost(obj.getAsJsonPrimitive("negativeBoost").getAsFloat());
        return new BoostingQuery(positiveQuery, negativeQuery, negativeBoost);
    }

    private Query buildBoolean(JsonObject obj) {
        BooleanQuery bq = new BooleanQuery();
        for (BooleanClause.OCCUR occur : BooleanClause.OCCUR.values()) {
            JsonArray qArr = obj.getAsJsonArray(occur.toString().toLowerCase(Locale.US));
            if (qArr != null) {
                for (JsonElement el : qArr) {
                    JsonObject clause = (JsonObject) el.getAsJsonObject();
                    Set<String> keys = clause.keySet();
                    if (keys.size() != 2) {
                        throw new IllegalArgumentException("boolean clause must have 2 entries: '"+
                                QUERY_STRING_NAME + "' and a query");
                    }
                    JsonObject queryObject = null;
                    String queryStringName = null;
                    String queryType = null;
                    for (String key : keys) {
                        if (key.equals(QUERY_STRING_NAME)) {
                            queryStringName = clause.get(QUERY_STRING_NAME).getAsJsonPrimitive().getAsString();
                        } else {
                            queryType = key;
                            queryObject = clause.getAsJsonObject(key);
                        }
                    }
                    if (queryStringName == null) {
                        throw new IllegalArgumentException("boolean clause must have a '"+QUERY_STRING_NAME+
                            "' entry");
                    }
                    if (queryObject == null) {
                        throw new IllegalArgumentException("boolean clause must contain a query");
                    }
                    Query child = buildQuery(queryType, queryObject);
                    if (! (child instanceof SingleStringQuery)) {
                        throw new IllegalArgumentException("For now, boolean clauses may only contain SingleStringQueries");
                    }
                    bq.addClause(new BooleanClause(queryStringName, occur, (SingleStringQuery)child));
                }
            }
        }
        return bq;
    }

    Query buildMultiMatch(JsonObject obj) {
        MultiMatchQuery q = new MultiMatchQuery();
        deserializeMultiField((MultiFieldQuery)q, obj);
        if (obj.has("boost")) {
            q.setBoost(new Boost(obj.getAsJsonPrimitive("boost").getAsFloat()));
        }
        String type = obj.get("type").getAsString();
        if (obj.has("fuzziness") && ! type.equals("phrase") && ! type.equals("cross_fields")) {
            q.setFuzziness(new Fuzziness(obj.getAsJsonPrimitive("fuzziness").getAsFloat()));
        }
        q.setMultiMatchType(new MultiMatchType(obj.get("type").getAsString()));
        return q;
    }

    Query buildEDisMax(JsonObject obj) {
        Query q = new EDisMaxQuery();
        deserializeDisMax((DisMaxQuery)q, obj);
        return q;
    }

    Query buildDisMax(JsonObject obj) {
        Query q = new DisMaxQuery();
        deserializeDisMax((DisMaxQuery)q, obj);
        return q;
    }

    void deserializeDisMax(DisMaxQuery q, JsonObject obj) {
        List<String> pfs = toStringList(obj.get("pf"));
        if (pfs.size() > 0) {
            PF pf = new PF();
            for (String f : toStringList(obj.get("pf"))) {
                pf.add(new WeightableField(f));
            }
            q.setPf(pf);
        }
        //TODO -- stub add in bq and bf
        /*
        if (obj.has("bq")) {
            BQ bq = new BQ(obj.get("bq").getAsString());
        }

        BF bf = new BF();
        */
        deserializeMultiField((MultiFieldQuery)q, obj);
    }

    void deserializeMultiField(MultiFieldQuery q, JsonObject obj) {
        QF qf = new QF();
        int fields = 0;
        for (String f : toStringList(obj.get("qf"))) {
            qf.add(new WeightableField(f));
            fields++;
        }
        if (fields == 0) {
            throw new IllegalArgumentException("must specify qf parameter in a multimatch with at least one field");
        }
        q.setQF(qf);
        if (obj.has(TIE)) {
            TIE tie = new TIE(obj.get(TIE).getAsFloat());
            q.setTie(tie);
        }
    }

    private Feature buildParam(String parameterName, JsonElement element) {
        if (element == null || element.isJsonNull()) {
            return null;
        }
        if (element.isJsonArray() && ((JsonArray)element).size() == 0) {
            return null;
        }
        Class paramClass = determineClass(parameterName);
        if (WeightableListFeature.class.isAssignableFrom(paramClass)) {
            return buildWeightableListFeature(parameterName, element);
        } else if (StringFeature.class.isAssignableFrom(paramClass)) {
            return buildStringFeature(parameterName, element);
        } else if (FloatFeature.class.isAssignableFrom(paramClass)) {
            return buildFloatFeature(parameterName, element);
        } else if (StringListFeature.class.isAssignableFrom(paramClass)) {
            return buildStringListFeature(parameterName, element);
        } else {
            throw new IllegalArgumentException("I regret I don't know how to build: "+parameterName);
        }
    }

    private Feature buildStringListFeature(String name, JsonElement element) {
        try {
            Class clazz = Class.forName(getClassName(name));
            if (!(StringListFeature.class.isAssignableFrom(clazz))) {
                throw new IllegalArgumentException(getClassName(name) + " must be assignable from WeightableListFeatureFactory");
            }
            int minSetSize = 0;
            int maxSetSize = 0;
            List<String> strings = new ArrayList<>();
            if (element.isJsonObject()) {
                JsonObject obj = (JsonObject)element;
                if (obj.has(FeatureFactorySerializer.MIN_SET_SIZE_KEY)) {
                    minSetSize = obj.get(FeatureFactorySerializer.MIN_SET_SIZE_KEY).getAsInt();
                }
                if (obj.has(FeatureFactorySerializer.MAX_SET_SIZE_KEY)) {
                    maxSetSize = obj.get(FeatureFactorySerializer.MAX_SET_SIZE_KEY).getAsInt();
                }
                if (!obj.has(VALUES_KEY)) {
                    throw new IllegalArgumentException(name + " must have a "+VALUES_KEY);
                }
                strings = toStringList(obj.get(VALUES_KEY));
            } else {
                strings = toStringList(element);
                minSetSize = 0;
                maxSetSize = strings.size();
            }
            Constructor constructor = null;

            constructor = clazz.getConstructor(List.class, int.class, int.class);
            return (StringListFeature)constructor.newInstance(strings, minSetSize, maxSetSize);
        } catch (Exception e) {
            throw new JsonParseException(e.getMessage());
        }
    }

    private Feature buildFloatFeature(String name, JsonElement element) {
        try {
            Class clazz = Class.forName(getClassName(name));
        if (!(FloatFeature.class.isAssignableFrom(clazz))) {
            throw new IllegalArgumentException(getClassName(name) + " must be assignable from WeightableListFeatureFactory");
        }
        Constructor constructor = null;

            constructor = clazz.getConstructor(float.class);
            return (FloatFeature)constructor.newInstance(element.getAsFloat());
        } catch (Exception e) {
            throw new JsonParseException(e.getMessage());
        }
    }

    private Feature buildStringFeature(String name, JsonElement element) {
        try {
        Class clazz = Class.forName(getClassName(name));
        if (!(FloatFeature.class.isAssignableFrom(clazz))) {
            throw new IllegalArgumentException(getClassName(name) + " must be assignable from WeightableListFeatureFactory");
        }
        Constructor constructor = null;
            constructor = clazz.getConstructor(Float.class);
            return (StringFeature)constructor.newInstance(element.getAsString());
        } catch (Exception e) {
            throw new JsonParseException(e.getMessage());
        }
    }

    private WeightableListFeature buildWeightableListFeature(String name, JsonElement element) {
        WeightableListFeature weightableListFeature;
        try {
            Class clazz = Class.forName(getClassName(name));
            if (!(WeightableListFeature.class.isAssignableFrom(clazz))) {
                throw new IllegalArgumentException(getClassName(name) + " must be assignable from WeightableListFeatureFactory");
            }
            weightableListFeature = (WeightableListFeature)clazz.newInstance();
        } catch (Exception e) {
            throw new JsonParseException(e.getMessage());
        }

        if (element.isJsonPrimitive()) {
            weightableListFeature.add(new WeightableField(element.getAsString()));
        } else if (element.isJsonArray()) {
            for (JsonElement e : (JsonArray)element) {
                if (e.isJsonNull()) {
                    LOG.warn("Json null in list for: "+name+" ?! Skipping.");
                    continue;
                }
                weightableListFeature.add(new WeightableField(e.getAsString()));
            }
        }
        return weightableListFeature;
    }


    @Override
    public JsonElement serialize(Query query, Type type, JsonSerializationContext jsonSerializationContext) {
        return serializeQuery(query);
    }

    private JsonElement serializeQuery(Query query) {
        JsonObject jsonObject = new JsonObject();
        if (query instanceof EDisMaxQuery) {
            jsonObject.add(EDISMAX, serializeEDisMax((EDisMaxQuery)query));
        } else if (query instanceof DisMaxQuery) {
            jsonObject.add(DISMAX, serializeDisMax((DisMaxQuery)query));
        } else if (query instanceof LuceneQuery) {
            jsonObject.add(LUCENE, serializeLucene((LuceneQuery)query));
        } else if (query instanceof TermsQuery) {
            jsonObject.add(TERMS, serializeTerms((TermsQuery)query));
        } else if (query instanceof TermQuery) {
            jsonObject.add(TERM, serializeTerm((TermQuery)query));
        } else if (query instanceof MultiMatchQuery) {
            jsonObject.add(MULTI_MATCH, serializeMultiMatchQuery((MultiMatchQuery)query));
        } else if (query instanceof BooleanQuery) {
            jsonObject.add(BOOL, serializeBoolean((BooleanQuery)query));
        } else if (query instanceof BoostingQuery) {
            jsonObject.add(BOOSTING, serializeBoosting((BoostingQuery)query));
        } else {
            throw new IllegalArgumentException("I'm sorry, I don't yet support: "+query.getClass());
        }
        return jsonObject;
    }

    private JsonElement serializeBoosting(BoostingQuery query) {
        JsonObject ret = new JsonObject();
        JsonObject posObj = new JsonObject();
        JsonObject serializedPosQuery = serializeQuery(query.getPositiveQuery()).getAsJsonObject();
        String posQType = JsonUtil.getSingleChildName(serializedPosQuery);
        posObj.add(posQType, serializedPosQuery.get(posQType));
        ret.add("positive", posObj);

        JsonObject negObj = new JsonObject();
        JsonObject serializedNegQuery = serializeQuery(
                query.getNegativeQuery()).getAsJsonObject();
        String negQType = JsonUtil.getSingleChildName(serializedNegQuery);
        negObj.add(negQType, serializedNegQuery.get(negQType));
        ret.add("negative", negObj);
        ret.add("negativeBoost", new JsonPrimitive(query.getNegativeBoost().getValue()));
        return ret;
    }

    private JsonElement serializeBoolean(BooleanQuery bq) {
        JsonObject ret = new JsonObject();
        for (BooleanClause.OCCUR occur : BooleanClause.OCCUR.values()) {
            JsonArray jsonArray = new JsonArray();
            for (BooleanClause clause : bq.get(occur)) {
                JsonObject clauseObj = new JsonObject();
                clauseObj.addProperty(QUERY_STRING_NAME, clause.getQueryStringName());
                JsonObject serializedQuery = serializeQuery(clause.getQuery()).getAsJsonObject();
                String qType = JsonUtil.getSingleChildName(serializedQuery);
                clauseObj.add(qType, serializedQuery.get(qType));
                jsonArray.add(clauseObj);
            }
            ret.add(occur.toString().toLowerCase(Locale.US), jsonArray);
        }
        return ret;
    }

    private JsonElement serializeMultiMatchQuery(MultiMatchQuery query) {
        JsonObject obj = new JsonObject();
        obj.add("type", new JsonPrimitive(query.getMultiMatchType().getFeature()));
        if (!MathUtil.equals(query.getBoost().getValue(), 1.0f, 0.01f)) {
            obj.add("boost", new JsonPrimitive(query.getBoost().getValue()));
        }
        if (!MathUtil.equals(query.getFuzziness().getValue(), 0.0f, 0.1f)) {
            obj.add("fuzziness", new JsonPrimitive(query.getFuzziness().getValue()));
        }
        serializeMultiFieldComponents(query, obj);
        return obj;
    }

    private JsonElement serializeTerm(TermQuery query) {
        JsonObject obj = new JsonObject();
        obj.add("term", new JsonPrimitive(query.getTerm()));
        obj.add("field", new JsonPrimitive(query.getField()));
        return obj;

    }

    private JsonElement serializeTerms(TermsQuery query) {
        JsonObject obj = new JsonObject();
        obj.add("terms", stringListJsonArr(query.getTerms()));
        obj.add("field", new JsonPrimitive(query.getField()));
        return obj;
    }

    private JsonElement serializeLucene(LuceneQuery query) {
        JsonObject object = new JsonObject();
        object.addProperty("queryString", query.getQueryString());
        object.addProperty("defaultField", query.getDefaultField());
        object.addProperty("q.op", query.getQueryOperator().toString());
        return object;
    }

    private JsonElement serializeDisMax(DisMaxQuery query) {
        JsonObject obj = new JsonObject();
        serializeDisMaxComponents(query, obj);
        serializeMultiFieldComponents((MultiFieldQuery)query, obj);
        return obj;
    }

    private JsonElement serializeEDisMax(EDisMaxQuery query) {
        JsonObject obj = new JsonObject();
        serializeEDisMaxComponents(query, obj);
        serializeDisMaxComponents((DisMaxQuery)query, obj);
        serializeMultiFieldComponents((MultiFieldQuery)query, obj);
        return obj;
    }

    private void serializeEDisMaxComponents(EDisMaxQuery query, JsonObject obj) {
        //stub
    }

    private void serializeDisMaxComponents(DisMaxQuery query, JsonObject obj) {
        //stub
//        throw new IllegalArgumentException("not yet supported");
    }

    private void serializeMultiFieldComponents(MultiFieldQuery query, JsonObject obj) {
        obj.add("qf", serializeFeature(query.getQF()));
        if (query.getTie() != null) {
            obj.add(TIE, serializeFeature(query.getTie()));
        }
    }

    private JsonElement serializeFeature(Feature feature) {
        if (feature == null) {
            return new JsonArray();
        }

        if (feature instanceof WeightableListFeature) {
            JsonObject ret = new JsonObject();
            JsonElement jsonFields;
            List<WeightableField> fields = ((WeightableListFeature)feature).getWeightableFields();
            if (fields.size() > 1) {
                jsonFields = new JsonArray();
                for (WeightableField f : ((WeightableListFeature) feature).getWeightableFields()) {
                    ((JsonArray)jsonFields).add(f.toString());
                }
            } else if (fields.size() == 1){
                jsonFields = new JsonPrimitive(fields.get(0).toString());
            } else {
                jsonFields = JsonNull.INSTANCE;
            }
            return jsonFields;
        } else if (feature instanceof FloatFeature) {
            return new JsonPrimitive(((FloatFeature)feature).getValue());
        } else if (feature instanceof StringFeature) {
            return new JsonPrimitive(feature.toString());
        } else if (feature instanceof StringListFeature) {
            StringListFeature stringListFeature = (StringListFeature)feature;
            List<String> strings = stringListFeature.getAll();
            JsonElement valuesElement;
            if (strings.size() == 0) {
                valuesElement = JsonNull.INSTANCE;
            } else if (strings.size() == 1) {
                valuesElement = new JsonPrimitive(strings.get(0));
            } else {
                valuesElement = new JsonArray();
                for (String s : strings) {
                    ((JsonArray)valuesElement).add(s);
                }
            }
            return valuesElement;
        } else {
            throw new IllegalArgumentException("not yet implemented: "+feature);
        }
    }
}

