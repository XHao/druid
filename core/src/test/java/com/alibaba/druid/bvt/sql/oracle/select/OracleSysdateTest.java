/*
 * Copyright 1999-2017 Alibaba Group Holding Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.alibaba.druid.bvt.sql.oracle.select;

import com.alibaba.druid.DbType;
import com.alibaba.druid.sql.OracleTest;
import com.alibaba.druid.sql.SQLUtils;
import com.alibaba.druid.sql.ast.SQLStatement;
import com.alibaba.druid.sql.dialect.oracle.ast.expr.OracleSysdateExpr;
import com.alibaba.druid.sql.visitor.SQLASTOutputVisitor;
import com.alibaba.druid.util.JdbcConstants;

import java.util.List;

public class OracleSysdateTest extends OracleTest {
    private DbType dbType = JdbcConstants.ORACLE;

    public void test_0() throws Exception {
        OracleSysdateExpr sysdate = new OracleSysdateExpr();

        StringBuilder buf = new StringBuilder();
        SQLASTOutputVisitor v = new SQLASTOutputVisitor(buf, DbType.oracle);
        sysdate.accept(v);
        assertEquals("SYSDATE", buf.toString());
    }
}
