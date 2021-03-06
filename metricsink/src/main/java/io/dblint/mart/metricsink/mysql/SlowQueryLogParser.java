package io.dblint.mart.metricsink.mysql;

import io.dblint.mart.metricsink.util.MetricAgentException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class SlowQueryLogParser {
  private static Logger logger = LoggerFactory.getLogger(SlowQueryLogParser.class);

  static Pattern tsLine = Pattern.compile("^# Time: ");
  static Pattern uhLine = Pattern.compile("# User@Host: (.*\\[.*\\])\\s*@\\s*"
      + "\\[(.*)\\]\\s*Id:\\s*(\\d+)");
  static Pattern queryMetadata = Pattern.compile("# Query_time: ([\\d\\.]+)\\s*"
      + "Lock_time: ([\\d\\.]+)\\s*Rows_sent: (\\d+)\\s*Rows_examined: (\\d+)");
  static Pattern setStatement = Pattern.compile("SET\\s+timestamp=([0-9]+)[,|;]");
  static Pattern useStatement = Pattern.compile("^use\\s+.+;");
  static Pattern comments = Pattern.compile("/\\*(.*)\\*/");
  static Pattern indexNotUsedMessage = Pattern.compile("index not used");

  static void parseUhLine(RewindBufferedReader reader, UserQuery userQuery)
      throws IOException, MetricAgentException {
    String line = reader.readLine();
    Matcher matcher = uhLine.matcher(line);
    if (matcher.find()) {
      userQuery.setUserHost(matcher.group(1));
      userQuery.setIpAddress(matcher.group(2));
      userQuery.setConnectionId(matcher.group(3));
    } else {
      throw new MetricAgentException("Line (" + reader.getLineNumber() + ") "
          + "did not match User Header pattern: '" + line
          + "' at " + matcher.toString());
    }
  }

  static void parseQueryMetadataLine(RewindBufferedReader reader, UserQuery userQuery)
      throws IOException, MetricAgentException {
    String line = reader.readLine();
    Matcher matcher = queryMetadata.matcher(line);
    if (matcher.find()) {
      userQuery.setQueryTime(Double.parseDouble(matcher.group(1)));
      userQuery.setLockTime(Double.parseDouble(matcher.group(2)));
      userQuery.setRowsSent(Long.parseLong(matcher.group(3)));
      userQuery.setRowsExamined(Long.parseLong(matcher.group(4)));
    } else {
      throw new MetricAgentException("Line (" + reader.getLineNumber() + ") "
          + "did not match Query Metadata pattern: '" + line
          + "' at " + matcher.toString());
    }
  }

  static void parseUseStatement(RewindBufferedReader reader)
      throws IOException {
    String line = reader.readLine();
    Matcher matcher = useStatement.matcher(line);
    if (!matcher.find()) {
      reader.rewind(line);
    }
  }

  static void parseSetStatement(RewindBufferedReader reader, UserQuery userQuery)
      throws IOException, MetricAgentException {
    String line = reader.readLine();
    Matcher matcher = setStatement.matcher(line);
    if (matcher.find()) {
      ZonedDateTime logTime = ZonedDateTime.ofInstant(
          Instant.ofEpochSecond(Long.parseLong(matcher.group(1))),
          ZoneId.of("UTC"));
      userQuery.setZonedLogTime(logTime);
    } else {
      throw new MetricAgentException("Line (" + reader.getLineNumber() + ") "
          + "did not match Set Statement pattern: '" + line
          + "' at " + matcher.toString());
    }
  }

  static String replaceComments(String line) {
    return line.replaceAll("/\\*.*?\\*/", "");
  }

  static boolean newSection(String line) {
    return tsLine.matcher(line).find();
  }

  static boolean newQuerySection(String line) {
    return uhLine.matcher(line).matches();
  }

  static UserQuery parseQuery(RewindBufferedReader bufferedReader)
      throws IOException, MetricAgentException {

    UserQuery query = new UserQuery();
    parseUhLine(bufferedReader, query);
    parseQueryMetadataLine(bufferedReader, query);
    parseUseStatement(bufferedReader);
    parseSetStatement(bufferedReader, query);
    query.setQuery(replaceComments(bufferedReader.readLine()));
    return query;
  }

  static List<UserQuery> parseTimeSection(RewindBufferedReader bufferedReader)
      throws IOException, MetricAgentException {
    List<UserQuery> userQueries = new ArrayList<>();
    String line = bufferedReader.readLine();
    while (line != null && newQuerySection(line)) {
      bufferedReader.rewind(line);
      userQueries.add(parseQuery(bufferedReader));
      line = bufferedReader.readLine();
    }

    if (line != null) {
      bufferedReader.rewind(line);
    }

    return userQueries.stream().filter(userQuery ->
        !indexNotUsedMessage.matcher(userQuery.getQuery()).find())
        .collect(Collectors.toList());
  }

  /**
   * Parses a MySql slow query log file and return a list of queries.
   * @param br BufferReader of the slow query log file
   * @return A list of queries in the log file
   * @throws IOException An exception is thrown if the log file cannot be read successfully
   */
  public static List<UserQuery> parseLog(RewindBufferedReader br)
      throws IOException, MetricAgentException {
    List<UserQuery> userQueries = new ArrayList<>();

    //Read file header
    int headerLines = 3;
    String lastLine = null;
    while (br.ready() && headerLines > 0) {
      lastLine = br.readLine();
      headerLines--;
    }

    if (headerLines > 0) {
      if (lastLine != null && lastLine.strip().startsWith("None")) {
        logger.info("No slow queries");
        return userQueries;
      }
      throw new MetricAgentException("Incomplete slow query log");
    }

    while (br.ready()) {
      String line = br.readLine();
      if (newSection(line)) {
        logger.debug("New Section at line: " + br.getLineNumber());
        userQueries.addAll(parseTimeSection(br));
      }
    }
    return userQueries;
  }
}
