package io.dblint.mart.analyses.redshift;

import com.codahale.metrics.Counter;
import com.codahale.metrics.MetricRegistry;
import io.dblint.mart.metricsink.redshift.UserQuery;
import io.dblint.mart.sqlplanner.redshift.QueryClasses;
import io.dblint.mart.sqlplanner.redshift.RedshiftClassifier;
import org.apache.calcite.sql.parser.SqlParseException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

class Etl {

  static class Result {
    final List<Gantt.Entry> gantt;
    final List<Gantt.TimeSlice> timeSlices;
    final Dag.Graph dag;
    final List<Dag.Phase> phases;
    final List<QueryInfo> queries;

    Result(List<Gantt.Entry> gantt, List<Gantt.TimeSlice> timeSlices,
           Dag.Graph dag, List<Dag.Phase> phases, List<QueryInfo> queries) {
      this.gantt = gantt;
      this.timeSlices = timeSlices;
      this.dag = dag;
      this.phases = phases;
      this.queries = queries;
    }
  }

  private static Logger logger = LoggerFactory.getLogger(Etl.class);
  private static int longDmlSec = 30;

  private Counter numQueries;
  private Counter numParsed;
  private Counter numInserts;
  private Counter numInsertsWithSelects;
  private Counter numLongDml;
  private Counter numMaintenanceQueries;
  private Counter numCtasQueries;
  private Counter numUnloadQueries;
  private Counter numCopyQueries;

  private RedshiftClassifier classifier;

  Etl(MetricRegistry registry) {
    numQueries = registry.counter("io.dblint.Etl.numQueries");
    numParsed = registry.counter("io.dblint.Etl.numParsed");
    numInserts = registry.counter("io.dblint.Etl.numInserts");
    numInsertsWithSelects = registry.counter("io.dblint.Etl.numInsertSelects");
    numLongDml = registry.counter("io.dblint.Etl.numLongDml");
    numMaintenanceQueries = registry.counter("io.dblint.Etl.numMaintenanceQueries");
    numCtasQueries = registry.counter("io.dblint.Etl.numCtas");
    numUnloadQueries = registry.counter("io.dblint.Etl.numUnload");
    numCopyQueries = registry.counter("io.dblint.Etl.numCopy");

    classifier = new RedshiftClassifier();
  }

  Result analyze(List<UserQuery> userQueries) {
    numQueries.inc(userQueries.size());

    List<QueryInfo> queryInfos = null;
    longRunningQueries(userQueries);
    queryInfos = parse(userQueries);
    logger.info("Queries parsed");
    final Dag.Graph dag = Dag.buildGraph(queryInfos);
    logger.info("DAG created");
    final List<Dag.Phase> phases = Dag.topologicalSort(dag);
    logger.info("Phases created");
    final List<Gantt.Entry> gantt = Gantt.sort(queryInfos);
    logger.info("Gantt created");
    final List<Gantt.TimeSlice> timeSlices = Gantt.histogram(queryInfos);
    logger.info("Histogram created");

    logger.info("numQueries: " + numQueries.getCount());
    logger.info("numMaintenanceQueries: " + numMaintenanceQueries.getCount());
    logger.info("numParsed: " + numParsed.getCount());
    logger.info("numInserts: " + numInserts.getCount());
    logger.info("numInsertWithSelects: " + numInsertsWithSelects.getCount());
    logger.info("numCtas: " + numCtasQueries.getCount());
    logger.info("numUnload: " + numUnloadQueries.getCount());
    logger.info("numCopy: " + numCopyQueries.getCount());
    logger.info("numLongDml: " + numLongDml.getCount());
    return new Result(gantt, timeSlices, dag, phases, queryInfos);
  }

  private void longRunningQueries(List<UserQuery> queries) {
    logger.info("Long Duration Queries");
    queries.stream()
        .sorted((q1, q2) -> ((Double)q2.duration).compareTo(q1.duration))
        .limit(20)
        .forEach(q -> logger.info(q.toString()));
  }

  List<QueryInfo> parse(List<UserQuery> queries) {
    List<QueryInfo> queryInfos = new ArrayList<>();
    queries.forEach((query) -> {
      try {
        QueryClasses classes = classifier.classify(query.query);
        numParsed.inc();

        if (classes.maintenanceContext.isPassed()) {
          numMaintenanceQueries.inc();
        }

        if (classes.insertContext.isPassed()) {
          logger.debug("Passed Insert Query");
          logger.debug(classes.insertContext.getTargetTable());
          if (classes.insertContext.getSources().size() > 0) {
            logger.debug("Num Sources: " + classes.insertContext.getSources().size());
            numInsertsWithSelects.inc();
            queryInfos.add(new QueryInfo(query, classes));
            numLongDml.inc();
          }
          numInserts.inc();
        }

        if (classes.ctasContext.isPassed()) {
          numCtasQueries.inc();
          queryInfos.add(new QueryInfo(query, classes));
          numLongDml.inc();
        }

        if (classes.unloadContext.isPassed()) {
          logger.info("Unload duration:" + query.getDuration());
          numUnloadQueries.inc();
          queryInfos.add(new QueryInfo(query, classes));
          numLongDml.inc();
        }

        if (classes.copyContext.isPassed()) {
          numCopyQueries.inc();
          logger.info("Copy duration:" + query.getDuration());
          queryInfos.add(new QueryInfo(query, classes));
          numLongDml.inc();
        }

      } catch (SqlParseException exception) {
        logger.warn(query.query);
        logger.warn(exception.getMessage());
        logger.warn("~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~");
      }
    });
    return queryInfos;
  }
}
