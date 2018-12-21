package io.dblint.mart.sqlplanner.enums;

import io.dblint.mart.sqlplanner.visitors.InsertVisitor;
import io.dblint.mart.sqlplanner.visitors.LookupVisitor;
import org.apache.calcite.sql.SqlNode;

/**
 * Created by rvenkatesh on 9/9/18.
 */
public enum AnalyticsEnum implements QueryType {
  LOOKUP {
    @Override
    public boolean isPassed(SqlNode sqlNode) {
      LookupVisitor lookupVisitor = new LookupVisitor();
      sqlNode.accept(lookupVisitor);
      return lookupVisitor.isPassed();
    }
  },
  INSERT {
    @Override
    public boolean isPassed(SqlNode sqlNode) {
      InsertVisitor visitor = new InsertVisitor();
      sqlNode.accept(visitor);
      return visitor.isPassed();
    }
  }
}
