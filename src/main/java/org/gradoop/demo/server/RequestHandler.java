/*
 * Copyright © 2014 - 2021 Leipzig University (Database Research Group)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.gradoop.demo.server;

import org.apache.flink.api.java.ExecutionEnvironment;
import org.apache.flink.api.java.io.LocalCollectionOutputFormat;
import org.apache.flink.api.java.tuple.Tuple3;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.gradoop.demo.server.functions.AcceptNoneFilter;
import org.gradoop.demo.server.functions.LabelFilter;
import org.gradoop.demo.server.functions.LabelGroupReducer;
import org.gradoop.demo.server.functions.LabelMapper;
import org.gradoop.demo.server.functions.LabelReducer;
import org.gradoop.demo.server.functions.PropertyKeyMapper;
import org.gradoop.demo.server.pojo.AggFunctionArguments;
import org.gradoop.demo.server.pojo.DifferenceRequest;
import org.gradoop.demo.server.pojo.KeyFunctionArguments;
import org.gradoop.demo.server.pojo.KeyedGroupingRequest;
import org.gradoop.demo.server.pojo.SnapshotRequest;
import org.gradoop.flink.model.api.functions.AggregateFunction;
import org.gradoop.flink.model.api.functions.KeyFunction;
import org.gradoop.flink.model.impl.operators.aggregation.functions.average.AverageProperty;
import org.gradoop.flink.model.impl.operators.aggregation.functions.count.Count;
import org.gradoop.flink.model.impl.operators.aggregation.functions.max.MaxProperty;
import org.gradoop.flink.model.impl.operators.aggregation.functions.min.MinProperty;
import org.gradoop.flink.model.impl.operators.aggregation.functions.sum.SumProperty;
import org.gradoop.flink.model.impl.operators.keyedgrouping.GroupingKeys;
import org.gradoop.flink.model.impl.operators.keyedgrouping.KeyedGrouping;
import org.gradoop.temporal.io.impl.csv.TemporalCSVDataSource;
import org.gradoop.temporal.model.api.TimeDimension;
import org.gradoop.temporal.model.api.functions.TemporalPredicate;
import org.gradoop.temporal.model.impl.TemporalGraph;
import org.gradoop.temporal.model.impl.functions.predicates.All;
import org.gradoop.temporal.model.impl.functions.predicates.AsOf;
import org.gradoop.temporal.model.impl.functions.predicates.Between;
import org.gradoop.temporal.model.impl.functions.predicates.FromTo;
import org.gradoop.temporal.model.impl.operators.aggregation.functions.MaxDuration;
import org.gradoop.temporal.model.impl.operators.aggregation.functions.MaxTime;
import org.gradoop.temporal.model.impl.operators.aggregation.functions.MinDuration;
import org.gradoop.temporal.model.impl.operators.aggregation.functions.MinTime;
import org.gradoop.temporal.model.impl.operators.keyedgrouping.TemporalGroupingKeys;
import org.gradoop.temporal.model.impl.pojo.TemporalEdge;
import org.gradoop.temporal.model.impl.pojo.TemporalElement;
import org.gradoop.temporal.model.impl.pojo.TemporalGraphHead;
import org.gradoop.temporal.model.impl.pojo.TemporalVertex;
import org.gradoop.temporal.util.TemporalGradoopConfig;

import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.io.FileWriter;
import java.io.IOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoField;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalField;
import java.time.temporal.TemporalUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Handles REST requests to the server.
 */
@Path("")
public class RequestHandler {

  /**
   * The filename of the metadata json.
   */
  private final String META_FILENAME = "/metadata.json";

  /**
   * The Flink execution environment.
   */
  private static final ExecutionEnvironment ENV = ExecutionEnvironment.createLocalEnvironment();

  /**
   * The gradoop config.
   */
  private final TemporalGradoopConfig temporalConfig = TemporalGradoopConfig.createConfig(ENV);

  /**
   * Takes a database name via a POST request and returns the keys of all
   * vertex and edge properties, and a boolean value specifying if the property has a numerical
   * type. The return is a string in the JSON format, for easy usage in a JavaScript web page.
   *
   * @param databaseName name of the loaded database
   * @return  A JSON containing the vertices and edges property keys
   */
  @POST
  @Path("/keys/{databaseName}")
  @Produces("application/json;charset=utf-8")
  public Response getKeysAndLabels(@PathParam("databaseName") String databaseName) {
    URL meta = RequestHandler.class.getResource("/data/" + databaseName + META_FILENAME);

    try {
      if (meta == null) {
        JSONObject result = computeKeysAndLabels(databaseName);
        if (result == null) {
          return Response.serverError().build();
        }
        return Response.ok(result.toString()).build();
      } else {
        return Response.ok(readKeysAndLabels(databaseName).toString()).build();
      }
    } catch (Exception e) {
      e.printStackTrace();
      // if any exception is thrown, return an error to the client
      return Response.serverError().build();
    }
  }

  /**
   * Get the complete graph in cytoscape-conform form.
   *
   * @param databaseName name of the database
   * @return Response containing the graph as a JSON, in cytoscape conform format.
   * @throws JSONException if JSON creation fails
   * @throws IOException if reading fails
   */
  @POST
  @Path("/graph/{databaseName}")
  @Produces("application/json;charset=utf-8")
  public Response getGraph(@PathParam("databaseName") String databaseName) throws Exception {
    String path = RequestHandler.class.getResource("/data/" + databaseName).getPath();

    TemporalCSVDataSource source = new TemporalCSVDataSource(path, temporalConfig);
    TemporalGraph graph = source.getTemporalGraph();
    String json = CytoJSONBuilder.getJSONString(
      graph.getGraphHead().collect(),
      graph.getVertices().collect(),
      graph.getEdges().collect());

    return Response.ok(json).build();
  }

  @POST
  @Path("/keyedgrouping")
  @Produces("application/json;charset=utf-8")
  public Response getData(KeyedGroupingRequest request) {
    String databaseName = request.getDbName();

    String path = RequestHandler.class.getResource("/data/" + databaseName).getPath();

    List<KeyFunction<TemporalVertex,?>> vertexKeyFunctions = new ArrayList<>();
    List<KeyFunction<TemporalEdge,?>> edgeKeyFunctions = new ArrayList<>();
    List<AggregateFunction> vertexAggregates = new ArrayList<>();
    List<AggregateFunction> edgeAggregates = new ArrayList<>();

    for (KeyFunctionArguments keyFunction : request.getKeyFunctions()) {
      if (keyFunction.getType().equals("vertex")) {
        // We have a vertex key function
        addKeyFunctionToList(vertexKeyFunctions, keyFunction);
      } else if (keyFunction.getType().equals("edge")) {
        // We have a edge key function
        addKeyFunctionToList(edgeKeyFunctions, keyFunction);
      } else {
        return Response
          .serverError()
          .type(MediaType.TEXT_HTML_TYPE)
          .entity("A key function found with a element type other than [vertex,edge].")
          .build();
      }
    }

    for (AggFunctionArguments aggFunction : request.getAggFunctions()) {
      if (aggFunction.getType().equals("vertex")) {
        // We have a vertex agg function
        addAggFunctionToList(vertexAggregates, aggFunction);
      } else if (aggFunction.getType().equals("edge")) {
        // We have an edge agg function
        addAggFunctionToList(edgeAggregates, aggFunction);
      } else {
        return Response
          .serverError()
          .type(MediaType.TEXT_HTML_TYPE)
          .entity("A aggregate function found with a element type other than [vertex,edge].")
          .build();
      }
    }

    TemporalCSVDataSource source = new TemporalCSVDataSource(path, temporalConfig);

    TemporalGraph graph = source.getTemporalGraph();

    // If no edges are requested, remove them as early as possible.
    if(request.getFilterAllEdges()) {
      graph = graph.subgraph(new LabelFilter<>(request.getVertexFilters()),
        new AcceptNoneFilter<>());
    } else {
      graph = graph.subgraph(new LabelFilter<>(request.getVertexFilters()),
        new LabelFilter<>(request.getEdgeFilters()));
    }

    graph = graph.callForGraph(
      new KeyedGrouping<>(vertexKeyFunctions, vertexAggregates, edgeKeyFunctions, edgeAggregates));

    return createResponse(graph);
  }

  @POST
  @Path("/snapshot")
  @Produces("application/json;charset=utf-8")
  public Response getData(SnapshotRequest request) {

    //load the database
    String databaseName = request.getDbName();

    String path = RequestHandler.class.getResource("/data/" + databaseName).getPath();

    TemporalCSVDataSource source = new TemporalCSVDataSource(path, temporalConfig);

    TemporalGraph graph = source.getTemporalGraph();

    DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    LocalDateTime time1 = LocalDateTime.parse(request.getTimestamp1(), formatter);
    LocalDateTime time2 = LocalDateTime.parse(request.getTimestamp2(), formatter);

    TemporalPredicate predicate;

    switch (request.getPredicate()) {
    case "asOf":
      predicate = new AsOf(time1);
      break;
    case "fromTo":
      predicate = new FromTo(time1, time2);
      break;
    case "betweenAnd":
      predicate = new Between(time1, time2);
      break;
    case "all":
    default:
      predicate = new All();
      break;

    }

    TimeDimension timeDimension;

    switch (request.getDimension()) {
    case "tx":
      timeDimension = TimeDimension.TRANSACTION_TIME;
      break;
    case "val":
    default:
      timeDimension = TimeDimension.VALID_TIME;
    }

    graph = graph.snapshot(predicate, timeDimension);

    return createResponse(graph);
  }

  @POST
  @Path("/difference")
  @Produces("application/json;charset=utf-8")
  public Response getData(DifferenceRequest request) {

    //load the database
    String databaseName = request.getDbName();

    String path = RequestHandler.class.getResource("/data/" + databaseName).getPath();

    TemporalCSVDataSource source = new TemporalCSVDataSource(path, temporalConfig);

    TemporalGraph graph = source.getTemporalGraph();

    DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    LocalDateTime time11 = LocalDateTime.parse(request.getTimestamp11(), formatter);
    LocalDateTime time12 = LocalDateTime.parse(request.getTimestamp12(), formatter);

    LocalDateTime time21 = LocalDateTime.parse(request.getTimestamp21(), formatter);
    LocalDateTime time22 = LocalDateTime.parse(request.getTimestamp22(), formatter);

    TemporalPredicate firstPredicate;

    switch (request.getFirstPredicate()) {
    case "asOf":
      firstPredicate = new AsOf(time11);
      break;
    case "fromTo":
      firstPredicate = new FromTo(time11, time12);
      break;
    case "betweenAnd":
      firstPredicate = new Between(time11, time12);
      break;
    case "all":
    default:
      firstPredicate = new All();
      break;
    }

    TemporalPredicate secondPredicate;

    switch (request.getSecondPredicate()) {
    case "asOf":
      secondPredicate = new AsOf(time21);
      break;
    case "fromTo":
      secondPredicate = new FromTo(time21, time22);
      break;
    case "betweenAnd":
      secondPredicate = new Between(time21, time22);
      break;
    case "all":
    default:
      secondPredicate = new All();
      break;
    }

    TimeDimension timeDimension;

    switch (request.getDimension()) {
    case "tx":
      timeDimension = TimeDimension.TRANSACTION_TIME;
      break;
    case "val":
    default:
      timeDimension = TimeDimension.VALID_TIME;
    }

    graph = graph.diff(firstPredicate, secondPredicate, timeDimension);

    return createResponse(graph);
  }

  private Response createResponse(TemporalGraph graph) {
    List<TemporalGraphHead> resultHead = new ArrayList<>();
    List<TemporalVertex> resultVertices = new ArrayList<>();
    List<TemporalEdge> resultEdges = new ArrayList<>();

    graph.getGraphHead().output(new LocalCollectionOutputFormat<>(resultHead));
    graph.getVertices().output(new LocalCollectionOutputFormat<>(resultVertices));
    graph.getEdges().output(new LocalCollectionOutputFormat<>(resultEdges));

    return getResponse(resultHead, resultVertices, resultEdges);
  }

  private Response getResponse(List<TemporalGraphHead> resultHead, List<TemporalVertex> resultVertices,
    List<TemporalEdge> resultEdges) {
    try {
      ENV.execute();
      // build the response JSON from the collections
      String json = CytoJSONBuilder.getJSONString(resultHead, resultVertices, resultEdges);
      return Response.ok(json).build();

    } catch (Exception e) {
      e.printStackTrace();
      // if any exception is thrown, return an error to the client
      return Response.serverError().build();
    }
  }

  /**
   * Compute property keys and labels.
   *
   * @return JSONObject containing property keys and labels
   */
  private JSONObject computeKeysAndLabels(String databaseName) throws IOException {

    String path = RequestHandler.class.getResource("/data/" + databaseName).getPath();

    TemporalCSVDataSource source = new TemporalCSVDataSource(path, temporalConfig);

    TemporalGraph graph = source.getTemporalGraph();

    JSONObject jsonObject = new JSONObject();

    //compute the vertex and edge property keys and return them
    try {
      jsonObject.put("vertexKeys", getVertexKeys(graph));
      jsonObject.put("edgeKeys", getEdgeKeys(graph));
      jsonObject.put("vertexLabels", getVertexLabels(graph));
      jsonObject.put("edgeLabels", getEdgeLabels(graph));
      String dataPath = RequestHandler.class.getResource("/data/" + databaseName).getFile();
      FileWriter writer = new FileWriter(dataPath + META_FILENAME);
      jsonObject.write(writer);
      writer.flush();
      writer.close();

      return jsonObject;
    } catch (Exception e) {
      e.printStackTrace();
      // if any exception is thrown, return an error to the client
      return null;
    }
  }

  /**
   * Read the property keys and labels from the buffered JSON.
   * @param databaseName name of the database
   * @return JSONObject containing the property keys and labels
   * @throws IOException if reading fails
   * @throws JSONException if JSON creation fails
   */
  private JSONObject readKeysAndLabels(String databaseName) throws IOException, JSONException {
    String dataPath = RequestHandler.class.getResource("/data/" + databaseName).getFile();
    String content =
      new String(Files.readAllBytes(Paths.get(dataPath + META_FILENAME)), StandardCharsets.UTF_8);

    return new JSONObject(content);
  }

  /**
   * Takes any given graph and creates a JSONArray containing the vertex property keys and a
   * boolean,
   * specifying it the property has a numerical type.
   *
   * @param graph input graph
   * @return  JSON array with property keys and boolean, that is true if the property type is
   * numercial
   * @throws Exception if the collecting of the distributed data fails
   */
  private JSONArray getVertexKeys(TemporalGraph graph) throws Exception {

    List<Tuple3<Set<String>, String, Boolean>> vertexKeys = graph.getVertices()
      .flatMap(new PropertyKeyMapper<>())
      .groupBy(1)
      .reduceGroup(new LabelGroupReducer())
      .collect();

    return buildArrayFromKeys(vertexKeys);
  }

  /**
   * Takes any given graph and creates a JSONArray containing the edge property keys and a boolean,
   * specifying it the property has a numerical type.
   *
   * @param graph input graph
   * @return  JSON array with property keys and boolean, that is true if the property type is
   * numercial
   * @throws Exception if the collecting of the distributed data fails
   */
  private JSONArray getEdgeKeys(TemporalGraph graph) throws Exception {

    List<Tuple3<Set<String>, String, Boolean>> edgeKeys = graph.getEdges()
      .flatMap(new PropertyKeyMapper<>())
      .groupBy(1)
      .reduceGroup(new LabelGroupReducer())
      .collect();

    return buildArrayFromKeys(edgeKeys);
  }

  /**
   * Convenience method.
   * Takes a set of tuples of property keys and booleans, specifying if the property is numerical,
   * and creates a JSON array containing the same data.
   *
   * @param keys set of tuples of property keys and booleans, that are true if the property type
   *             is numerical
   * @return JSONArray containing the same data as the input
   * @throws JSONException if the construction of the JSON fails
   */
  private JSONArray buildArrayFromKeys(List<Tuple3<Set<String>, String, Boolean>> keys)
    throws JSONException {
    JSONArray keyArray = new JSONArray();
    for(Tuple3<Set<String>, String, Boolean> key : keys) {
      JSONObject keyObject = new JSONObject();
      JSONArray labels = new JSONArray();
      key.f0.forEach(labels::put);
      keyObject.put("labels", labels);
      keyObject.put("name", key.f1);
      keyObject.put("numerical", key.f2);
      keyArray.put(keyObject);
    }
    return keyArray;
  }

  /**
   * Compute the labels of the vertices.
   *
   * @param graph logical graph
   * @return JSONArray containing the vertex labels
   * @throws Exception if the computation fails
   */
  private JSONArray getVertexLabels(TemporalGraph graph) throws Exception {
    List<Set<String>> vertexLabels = graph.getVertices()
      .map(new LabelMapper<>())
      .reduce(new LabelReducer())
      .collect();

    if(vertexLabels.size() > 0) {
      return buildArrayFromLabels(vertexLabels.get(0));
    } else {
      return new JSONArray();
    }
  }

  /**
   * Compute the labels of the edges.
   *
   * @param graph logical graph
   * @return JSONArray containing the edge labels
   * @throws Exception if the computation fails
   */
  private JSONArray getEdgeLabels(TemporalGraph graph ) throws Exception {
    List<Set<String>> edgeLabels = graph.getEdges()
      .map(new LabelMapper<>())
      .reduce(new LabelReducer())
      .collect();

    if(edgeLabels.size() > 0) {
      return buildArrayFromLabels(edgeLabels.get(0));
    } else {
      return new JSONArray();
    }
  }

  /**
   * Create a JSON array from the sets of labels.
   *
   * @param labels set of labels
   * @return JSON array of labels
   */
  private JSONArray buildArrayFromLabels(Set<String> labels) {
    JSONArray labelArray = new JSONArray();
    labels.forEach(labelArray::put);
    return labelArray;
  }

  private void addAggFunctionToList(
    List<AggregateFunction> aggregateFunctionList,
    AggFunctionArguments aggFunction) {
    switch (aggFunction.getAgg()) {
    case "count":
      aggregateFunctionList.add(new Count());
      break;
    case "minProp":
      aggregateFunctionList.add(new MinProperty(aggFunction.getProp()));
      break;
    case "maxProp":
      aggregateFunctionList.add(new MaxProperty(aggFunction.getProp()));
      break;
    case "avgProp":
      aggregateFunctionList.add(new AverageProperty(aggFunction.getProp()));
      break;
    case "sumProp":
      aggregateFunctionList.add(new SumProperty(aggFunction.getProp()));
      break;
    case "minTime":
      TimeDimension dimension = getTimeDimension(aggFunction.getDimension());
      TimeDimension.Field field = getPeriodBound(aggFunction.getPeriodBound());
      aggregateFunctionList.add(new MinTime("minTime_" + dimension + "_" + field, dimension, field));
      break;
    case "maxTime":
      dimension = getTimeDimension(aggFunction.getDimension());
      field = getPeriodBound(aggFunction.getPeriodBound());
      aggregateFunctionList.add(new MaxTime("maxTime_" + dimension + "_" + field, dimension, field));
      break;
    case "minDuration":
      dimension = getTimeDimension(aggFunction.getDimension());
      aggregateFunctionList.add(new MinDuration("minDuration_" + dimension, dimension));
      break;
    case "maxDuration":
      dimension = getTimeDimension(aggFunction.getDimension());
      aggregateFunctionList.add(new MaxDuration("maxDuration_" + dimension, dimension));
      break;
    case "avgDuration":
      dimension = getTimeDimension(aggFunction.getDimension());
      aggregateFunctionList.add(new MaxDuration("avgDuration_" + dimension, dimension));
      break;
    }
  }

  private <T extends TemporalElement> void addKeyFunctionToList(List<KeyFunction<T,?>> keyFunctionList, KeyFunctionArguments keyFunction) {
    switch (keyFunction.getKey()) {
    case "label":
      keyFunctionList.add(GroupingKeys.label());
      break;
    case "property":
      keyFunctionList.add(GroupingKeys.property(keyFunction.getProp()));
      break;
    case "timestamp":
      if (keyFunction.getField().equals("no")) {
        keyFunctionList.add(
          TemporalGroupingKeys.timeStamp(
            getTimeDimension(keyFunction.getDimension()),
            getPeriodBound(keyFunction.getPeriodBound())));
      } else {
        keyFunctionList.add(
          TemporalGroupingKeys.timeStamp(
            getTimeDimension(keyFunction.getDimension()),
            getPeriodBound(keyFunction.getPeriodBound()),
            getTemporalField(keyFunction.getField())));
      }
      break;
    case "interval":
      keyFunctionList.add(
        TemporalGroupingKeys.timeInterval(
          getTimeDimension(keyFunction.getDimension())));
      break;
    case "duration":
      keyFunctionList.add(
        TemporalGroupingKeys.duration(
          getTimeDimension(keyFunction.getDimension()),
          getTemporalUnit(keyFunction.getUnit())
        )
      );
      break;
    }
  }

  private TimeDimension getTimeDimension(String name) {
    return TimeDimension.valueOf(name);
  }

  private TimeDimension.Field getPeriodBound(String name) {
    return TimeDimension.Field.valueOf(name);
  }

  private TemporalField getTemporalField(String name) {
    switch (name) {
    case "year":
      return ChronoField.YEAR;
    case "month":
      return ChronoField.MONTH_OF_YEAR;
    case "weekOfYear":
      return ChronoField.ALIGNED_WEEK_OF_YEAR;
    case "weekOfMonth":
      return ChronoField.ALIGNED_WEEK_OF_MONTH;
    case "dayOfMonth":
      return ChronoField.DAY_OF_MONTH;
    case "dayOfYear":
      return ChronoField.DAY_OF_YEAR;
    case "hour":
      return ChronoField.HOUR_OF_DAY;
    case "minute":
      return ChronoField.MINUTE_OF_HOUR;
    case "second":
      return ChronoField.SECOND_OF_MINUTE;
    default:
      throw new IllegalArgumentException("Unknown time field: " + name);
    }
  }

  private TemporalUnit getTemporalUnit(String name) {
    return ChronoUnit.valueOf(name);
  }
}