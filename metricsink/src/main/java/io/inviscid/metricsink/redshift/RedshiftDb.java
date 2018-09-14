package io.inviscid.metricsink.redshift;

import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.core.mapper.reflect.ConstructorMapper;

import java.util.List;

public class RedshiftDb {
  final String url;
  final String user;
  final String password;
  final Jdbi jdbi;

  public RedshiftDb(String url, String user, String password) {
    this.url = url;
    this.user = user;
    this.password = password;
    this.jdbi = Jdbi.create(url, user, password);
  }

  List<QueryStats> getQueryStats(boolean inTest) {
    return jdbi.withHandle(handle -> {
      handle.registerRowMapper(ConstructorMapper.factory(QueryStats.class));
      return handle.createQuery(inTest ? QueryStats.getExtractQueryinTest() : QueryStats.getExtractQuery())
          .mapTo(QueryStats.class)
          .list();
    });
  }
}
