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
package com.alibaba.druid.sql.dialect.mysql.parser;

import com.alibaba.druid.DbType;
import com.alibaba.druid.sql.ast.*;
import com.alibaba.druid.sql.ast.expr.*;
import com.alibaba.druid.sql.ast.statement.*;
import com.alibaba.druid.sql.ast.statement.SQLForeignKeyImpl.Match;
import com.alibaba.druid.sql.ast.statement.SQLForeignKeyImpl.Option;
import com.alibaba.druid.sql.dialect.mysql.ast.MySqlPrimaryKey;
import com.alibaba.druid.sql.dialect.mysql.ast.MySqlUnique;
import com.alibaba.druid.sql.dialect.mysql.ast.MysqlForeignKey;
import com.alibaba.druid.sql.dialect.mysql.ast.MysqlPartitionSingle;
import com.alibaba.druid.sql.dialect.mysql.ast.expr.*;
import com.alibaba.druid.sql.dialect.mysql.ast.statement.MySqlCreateTableStatement;
import com.alibaba.druid.sql.parser.*;
import com.alibaba.druid.util.FnvHash;
import com.alibaba.druid.util.HexBin;
import com.alibaba.druid.util.MySqlUtils;
import com.alibaba.druid.util.StringUtils;
import org.apache.commons.lang3.tuple.Pair;

import java.sql.Types;
import java.util.Arrays;
import java.util.List;

public class MySqlExprParser extends SQLExprParser {
    public static final String[] AGGREGATE_FUNCTIONS;

    public static final long[] AGGREGATE_FUNCTIONS_CODES;

    public static final String[] SINGLE_WORD_TABLE_OPTIONS;

    public static final long[] SINGLE_WORD_TABLE_OPTIONS_CODES;

    static {
        String[] strings = {
                "AVG",
                "ANY_VALUE",
                "BIT_AND",
                "BIT_OR",
                "BIT_XOR",
                "COUNT",
                "GROUP_CONCAT",
                "LISTAGG",
                "JSON_ARRAYAGG",
                "JSON_OBJECTAGG",
                "MAX",
                "MIN",
                "STD",
                "STDDEV",
                "STDDEV_POP",
                "STDDEV_SAMP",
                "SUM",
                "VAR_POP",
                "VAR_SAMP",
                "VARIANCE",
        };

        AGGREGATE_FUNCTIONS_CODES = FnvHash.fnv1a_64_lower(strings, true);
        AGGREGATE_FUNCTIONS = new String[AGGREGATE_FUNCTIONS_CODES.length];
        for (String str : strings) {
            long hash = FnvHash.fnv1a_64_lower(str);
            int index = Arrays.binarySearch(AGGREGATE_FUNCTIONS_CODES, hash);
            AGGREGATE_FUNCTIONS[index] = str;
        }

        // https://dev.mysql.com/doc/refman/5.7/en/create-table.html
        String[] options = {"AUTO_INCREMENT", "AVG_ROW_LENGTH", /*"CHARACTER SET",*/ "CHECKSUM", "COLLATE", "COMMENT",
                "COMPRESSION", "CONNECTION", /*"{DATA|INDEX} DIRECTORY",*/ "DELAY_KEY_WRITE", "ENCRYPTION", "ENGINE",
                "INSERT_METHOD", "KEY_BLOCK_SIZE", "MAX_ROWS", "MIN_ROWS", "PACK_KEYS", "PASSWORD", "ROW_FORMAT",
                "STATS_AUTO_RECALC", "STATS_PERSISTENT", "STATS_SAMPLE_PAGES", "TABLESPACE", "UNION",
                "STORAGE_TYPE", "STORAGE_POLICY"};
        SINGLE_WORD_TABLE_OPTIONS_CODES = FnvHash.fnv1a_64_lower(options, true);
        SINGLE_WORD_TABLE_OPTIONS = new String[SINGLE_WORD_TABLE_OPTIONS_CODES.length];
        for (String str : options) {
            long hash = FnvHash.fnv1a_64_lower(str);
            int index = Arrays.binarySearch(SINGLE_WORD_TABLE_OPTIONS_CODES, hash);
            SINGLE_WORD_TABLE_OPTIONS[index] = str;
        }
    }

    public MySqlExprParser(Lexer lexer) {
        super(lexer, DbType.mysql);
        this.aggregateFunctions = AGGREGATE_FUNCTIONS;
        this.aggregateFunctionHashCodes = AGGREGATE_FUNCTIONS_CODES;
    }

    public MySqlExprParser(String sql) {
        this(new MySqlLexer(sql));
        this.lexer.nextToken();
    }

    public MySqlExprParser(String sql, SQLParserFeature... features) {
        super(new MySqlLexer(sql, features), DbType.mysql);
        this.aggregateFunctions = AGGREGATE_FUNCTIONS;
        this.aggregateFunctionHashCodes = AGGREGATE_FUNCTIONS_CODES;
        if (sql.length() > 6) {
            char c0 = sql.charAt(0);
            char c1 = sql.charAt(1);
            char c2 = sql.charAt(2);
            char c3 = sql.charAt(3);
            char c4 = sql.charAt(4);
            char c5 = sql.charAt(5);
            char c6 = sql.charAt(6);

            if (c0 == 'S' && c1 == 'E' && c2 == 'L' && c3 == 'E' && c4 == 'C' && c5 == 'T' && c6 == ' ') {
                lexer.reset(6, ' ', Token.SELECT);
                return;
            }

            if (c0 == 's' && c1 == 'e' && c2 == 'l' && c3 == 'e' && c4 == 'c' && c5 == 't' && c6 == ' ') {
                lexer.reset(6, ' ', Token.SELECT);
                return;
            }

            if (c0 == 'I' && c1 == 'N' && c2 == 'S' && c3 == 'E' && c4 == 'R' && c5 == 'T' && c6 == ' ') {
                lexer.reset(6, ' ', Token.INSERT);
                return;
            }

            if (c0 == 'i' && c1 == 'n' && c2 == 's' && c3 == 'e' && c4 == 'r' && c5 == 't' && c6 == ' ') {
                lexer.reset(6, ' ', Token.INSERT);
                return;
            }

            if (c0 == 'U' && c1 == 'P' && c2 == 'D' && c3 == 'A' && c4 == 'T' && c5 == 'E' && c6 == ' ') {
                lexer.reset(6, ' ', Token.UPDATE);
                return;
            }

            if (c0 == 'u' && c1 == 'p' && c2 == 'd' && c3 == 'a' && c4 == 't' && c5 == 'e' && c6 == ' ') {
                lexer.reset(6, ' ', Token.UPDATE);
                return;
            }

            if (c0 == '/' && c1 == '*' && (isEnabled(SQLParserFeature.OptimizedForParameterized) && !isEnabled(SQLParserFeature.TDDLHint))) {
                MySqlLexer mySqlLexer = (MySqlLexer) lexer;
                mySqlLexer.skipFirstHintsOrMultiCommentAndNextToken();
                return;
            }
        }
        this.lexer.nextToken();

    }

    public MySqlExprParser(String sql, boolean keepComments) {
        this(new MySqlLexer(sql, true, keepComments));
        this.lexer.nextToken();
    }

    public MySqlExprParser(String sql, boolean skipComment, boolean keepComments) {
        this(new MySqlLexer(sql, skipComment, keepComments));
        this.lexer.nextToken();
    }

    @Override
    protected SQLExpr primaryIdentifierRest(long hash_lower, String ident) {
        SQLExpr sqlExpr = null;
        if (hash_lower == FnvHash.Constants.VARCHAR && lexer.token() == Token.LITERAL_CHARS) {
            MySqlCharExpr mysqlChar = new MySqlCharExpr(lexer.stringVal());
            mysqlChar.setType("VARCHAR");
            sqlExpr = mysqlChar;
            lexer.nextToken();
        } else if (hash_lower == FnvHash.Constants.CHAR && lexer.token() == Token.LITERAL_CHARS) {
            MySqlCharExpr mysqlChar = new MySqlCharExpr(lexer.stringVal());
            mysqlChar.setType("CHAR");
            sqlExpr = mysqlChar;
            lexer.nextToken();
        } else if (ident.startsWith("0x") && (ident.length() % 2) == 0) {
            sqlExpr = new SQLHexExpr(ident.substring(2));
        } else if (hash_lower == FnvHash.Constants.JSON
                && lexer.token() == Token.LITERAL_CHARS) {
            sqlExpr = new SQLJSONExpr(lexer.stringVal());
            lexer.nextToken();
        } else if (hash_lower == FnvHash.Constants.DECIMAL
                && lexer.token() == Token.LITERAL_CHARS) {
            sqlExpr = new SQLDecimalExpr(lexer.stringVal());
            lexer.nextToken();
        } else if (hash_lower == FnvHash.Constants.DOUBLE
                && lexer.token() == Token.LITERAL_CHARS) {
            sqlExpr = new SQLDoubleExpr(lexer.stringVal());
            lexer.nextToken();
        } else if (hash_lower == FnvHash.Constants.FLOAT
                && lexer.token() == Token.LITERAL_CHARS) {
            sqlExpr = new SQLFloatExpr(lexer.stringVal());
            lexer.nextToken();
        } else if (hash_lower == FnvHash.Constants.SMALLINT
                && lexer.token() == Token.LITERAL_CHARS) {
            sqlExpr = new SQLSmallIntExpr(lexer.stringVal());
            lexer.nextToken();
        } else if (hash_lower == FnvHash.Constants.TINYINT && lexer.token() == Token.LITERAL_CHARS) {
            sqlExpr = new SQLTinyIntExpr(lexer.stringVal());
            lexer.nextToken();
        } else if (hash_lower == FnvHash.Constants.BIGINT && lexer.token() == Token.LITERAL_CHARS) {
            String strVal = lexer.stringVal();
            if (strVal.startsWith("--")) {
                strVal = strVal.substring(2);
            }
            sqlExpr = new SQLBigIntExpr(strVal);
            lexer.nextToken();
        } else if (hash_lower == FnvHash.Constants.INTEGER && lexer.token() == Token.LITERAL_CHARS) {
            String strVal = lexer.stringVal();
            if (strVal.startsWith("--")) {
                strVal = strVal.substring(2);
            }
            SQLIntegerExpr integerExpr = SQLIntegerExpr.ofIntOrLong(Long.parseLong(strVal));
            integerExpr.setType("INTEGER");
            sqlExpr = integerExpr;
            lexer.nextToken();
        } else if (hash_lower == FnvHash.Constants.REAL && lexer.token() == Token.LITERAL_CHARS) {
            sqlExpr = new SQLRealExpr(lexer.stringVal());
            lexer.nextToken();
        }

        return sqlExpr;
    }

    @Override
    protected SQLExpr primaryLiteralCharsRest(SQLExpr sqlExpr) {
        lexer.nextTokenValue();

        for (; ; ) {
            if (lexer.token() == Token.LITERAL_ALIAS) {
                String concat = ((SQLCharExpr) sqlExpr).getText();
                concat += lexer.stringVal();
                lexer.nextTokenValue();
                sqlExpr = new SQLCharExpr(concat);
            } else if (lexer.token() == Token.LITERAL_CHARS || lexer.token() == Token.LITERAL_NCHARS) {
                String concat = ((SQLCharExpr) sqlExpr).getText();
                concat += lexer.stringVal();
                lexer.nextTokenValue();
                sqlExpr = new SQLCharExpr(concat);
            } else {
                break;
            }
        }
        return sqlExpr;
    }

    @Override
    protected SQLExpr primaryLiteralNCharsRest(SQLExpr sqlExpr) {
        SQLMethodInvokeExpr concat = null;
        for (; ; ) {
            if (lexer.token() == Token.LITERAL_ALIAS) {
                if (concat == null) {
                    concat = new SQLMethodInvokeExpr("CONCAT");
                    concat.addArgument(sqlExpr);
                    sqlExpr = concat;
                }
                String alias = lexer.stringVal();
                lexer.nextToken();
                SQLCharExpr concat_right = new SQLCharExpr(alias.substring(1, alias.length() - 1));
                concat.addArgument(concat_right);
            } else if (lexer.token() == Token.LITERAL_CHARS || lexer.token() == Token.LITERAL_NCHARS) {
                if (concat == null) {
                    concat = new SQLMethodInvokeExpr("CONCAT");
                    concat.addArgument(sqlExpr);
                    sqlExpr = concat;
                }

                String chars = lexer.stringVal();
                lexer.nextToken();
                SQLCharExpr concat_right = new SQLCharExpr(chars);
                concat.addArgument(concat_right);
            } else {
                break;
            }
        }
        return sqlExpr;
    }

    @Override
    protected SQLExpr bitXorRestSUBGT() {
        if (lexer.token() == Token.LITERAL_CHARS || lexer.token() == Token.LITERAL_ALIAS) {
            return primary();
        } else {
            return expr();
        }
    }

    @Override
    protected SQLExpr primarySubLiteralAliasRest() {
        return new SQLCharExpr(lexer.stringVal());
    }

    @Override
    protected void primaryQues() {
        lexer.nextTokenValue();
    }

    @Override
    protected SQLExpr primaryDistinct(SQLExpr sqlExpr) {
        Lexer.SavePoint mark = lexer.mark();
        sqlExpr = new SQLIdentifierExpr(lexer.stringVal());
        lexer.nextToken();
        if (lexer.token() != Token.LPAREN) {
            lexer.reset(mark);
            throw new ParserException("ERROR. " + lexer.info());
        }
        return sqlExpr;
    }

    @Override
    protected SQLExpr methodRestAllowIdentifierMethodSpecific(String methodName, long hash_lower, SQLMethodInvokeExpr methodInvokeExpr) {
        if (hash_lower == FnvHash.Constants.MATCH) {
            return parseMatch();
        } else if (hash_lower == FnvHash.Constants.EXTRACT) {
            return parseExtract();
        } else if (hash_lower == FnvHash.Constants.POSITION) {
            return parsePosition();
        } else if (hash_lower == FnvHash.Constants.CONVERT) {
            methodInvokeExpr = new SQLMethodInvokeExpr(methodName, hash_lower);
            SQLExpr arg0 = this.expr();
            // Fix for using.
            Object exprUsing = arg0.getAttributes().get("USING");
            if (exprUsing instanceof String) {
                String charset = (String) exprUsing;
                methodInvokeExpr.setUsing(new SQLIdentifierExpr(charset));
                arg0.getAttributes().remove("USING");
            }
            methodInvokeExpr.addArgument(arg0);

            if (lexer.token() == Token.COMMA) {
                lexer.nextToken();
                SQLDataType dataType = this.parseDataType();
                SQLDataTypeRefExpr dataTypeRefExpr = new SQLDataTypeRefExpr(dataType);
                methodInvokeExpr.addArgument(dataTypeRefExpr);
            }

            if (lexer.token() == Token.USING || lexer.identifierEquals(FnvHash.Constants.USING)) {
                lexer.nextToken();
                SQLExpr using;
                if (lexer.token() == Token.STAR) {
                    lexer.nextToken();
                    using = new SQLAllColumnExpr();
                } else if (lexer.token() == Token.BINARY) {
                    using = new SQLIdentifierExpr(lexer.stringVal());
                    lexer.nextToken();
                } else {
                    using = this.primary();
                }
                methodInvokeExpr.setUsing(using);
            }

            accept(Token.RPAREN);

            return primaryRest(methodInvokeExpr);
        }
        return null;
    }

    @Override
    protected void exprListComma() {
        lexer.nextTokenValue();
    }

    @Override
    protected SQLBinaryOperator orRestGetOrOperator() {
        return !isEnabled(SQLParserFeature.PipesAsConcat) ? SQLBinaryOperator.BooleanOr : SQLBinaryOperator.Concat;
    }
    protected void parseDataTypeByte(StringBuilder typeName) {
        typeName.append(' ').append(lexer.stringVal());
        lexer.nextToken();
    }

    @Override
    protected void parseDataTypePrecision(StringBuilder typeName) {
        if (lexer.identifierEquals(FnvHash.Constants.PRECISION)) {
            typeName.append(' ').append(lexer.stringVal());
            lexer.nextToken();
        }
    }

    @Override
    protected SQLExpr parseColumnRestDefault() {
        SQLExpr defaultExpr;
        if (lexer.token() == Token.LITERAL_CHARS) {
            defaultExpr = new SQLCharExpr(lexer.stringVal());
            lexer.nextToken();
        } else {
            defaultExpr = bitOr();
        }
        return defaultExpr;
    }

    @Override
    protected void parseIndexSpecific(SQLIndexDefinition indexDefinition) {
        if (lexer.identifierEquals(FnvHash.Constants.USING)) {
            lexer.nextToken();
            indexDefinition.getOptions().setIndexType(lexer.stringVal());
            lexer.nextToken();
        } else if (lexer.identifierEquals("HASHMAP")) {
            lexer.nextToken();
            indexDefinition.setHashMapType(true);
            indexDefinition.getParent().putAttribute("ads.index", Boolean.TRUE);
        } else if (lexer.identifierEquals(FnvHash.Constants.HASH)) {
            lexer.nextToken();
            indexDefinition.setHashType(true);
            indexDefinition.getParent().putAttribute("ads.index", Boolean.TRUE);
        } else {
            indexDefinition.setName(name());
        }
    }

    @Override
    protected void parseIndexOptions(SQLIndexDefinition indexDefinition) {
        _opts:
        while (true) {
            if (lexer.token() == Token.COMMENT) {
                lexer.nextToken();
                indexDefinition.getOptions().setComment(primary());
            } else if (lexer.identifierEquals("INVISIBLE")) {
                lexer.nextToken();
                indexDefinition.getOptions().setInvisible(true);
            } else if (lexer.identifierEquals("VISIBLE")) {
                lexer.nextToken();
                indexDefinition.getOptions().setVisible(true);
            } else if (lexer.identifierEquals("GLOBAL")) {
                lexer.nextToken();
                indexDefinition.getOptions().setGlobal(true);
            } else if (lexer.identifierEquals("LOCAL")) {
                lexer.nextToken();
                indexDefinition.getOptions().setLocal(true);
            } else if (lexer.token() == Token.HINT && lexer.stringVal().trim().equals("!80000 INVISIBLE")) {
                lexer.nextToken();
                indexDefinition.getOptions().setInvisible(true);
            } else {
                switch (lexer.token()) {
                    case WITH:
                        Lexer.SavePoint mark = lexer.mark();
                        lexer.nextToken();
                        if (lexer.identifierEquals("PARSER")) {
                            lexer.nextToken();
                            indexDefinition.getOptions().setParserName(lexer.stringVal());
                            lexer.nextToken();
                            break;
                        }
                        lexer.reset(mark);
                        for (; ; ) {
                            if (lexer.token() == Token.WITH) {
                                lexer.nextToken();
                                // Part from original MySqlCreateTableParser.
                                if (lexer.token() == Token.INDEX) {
                                    lexer.nextToken();
                                    acceptIdentifier("ANALYZER");
                                    indexDefinition.setIndexAnalyzerName(name());
                                    continue;
                                } else if (lexer.identifierEquals(FnvHash.Constants.QUERY)) {
                                    lexer.nextToken();
                                    acceptIdentifier("ANALYZER");
                                    indexDefinition.setQueryAnalyzerName(name());
                                    continue;
                                } else if (lexer.identifierEquals(FnvHash.Constants.ANALYZER)) {
                                    lexer.nextToken();
                                    SQLName name = name();
                                    indexDefinition.setAnalyzerName(name);
                                    break;
                                } else if (lexer.identifierEquals("DICT")) {
                                    lexer.nextToken();
                                    indexDefinition.setWithDicName(name());
                                    continue;
                                }
                            }
                            break;
                        }
                        break;
                    case LOCK:
                        lexer.nextToken();
                        if (lexer.token() == Token.EQ) {
                            lexer.nextToken();
                        }
                        indexDefinition.getOptions().setLock(lexer.stringVal());
                        lexer.nextToken();
                        break;

                    case IDENTIFIER:
                        if (lexer.identifierEquals(FnvHash.Constants.KEY_BLOCK_SIZE)
                                || lexer.identifierEquals(FnvHash.Constants.BLOCK_SIZE)) {
                            lexer.nextToken();
                            if (lexer.token() == Token.EQ) {
                                lexer.nextToken();
                            }
                            indexDefinition.getOptions().setKeyBlockSize(expr());
                        } else if (lexer.identifierEquals(FnvHash.Constants.USING)) {
                            lexer.nextToken();
                            indexDefinition.getOptions().setIndexType(lexer.stringVal());
                            lexer.nextToken();
                        } else if (lexer.identifierEquals(FnvHash.Constants.ALGORITHM)) {
                            lexer.nextToken();
                            if (lexer.token() == Token.EQ) {
                                lexer.nextToken();
                            }
                            indexDefinition.getOptions().setAlgorithm(lexer.stringVal());
                            lexer.nextToken();
                        } else if (lexer.identifierEquals(FnvHash.Constants.DISTANCEMEASURE)) {
                            // Caution: Not in MySql documents.
                            SQLExpr key = new SQLIdentifierExpr(lexer.stringVal());
                            lexer.nextToken();
                            if (lexer.token() == Token.EQ) {
                                lexer.nextToken();
                            }
                            SQLAssignItem item = new SQLAssignItem(key, primary());
                            if (indexDefinition.getParent() != null) {
                                item.setParent(indexDefinition.getParent());
                            } else {
                                item.setParent(indexDefinition);
                            }
                            // Add both with same object.
                            indexDefinition.getOptions().getOtherOptions().add(item);
                            indexDefinition.getCompatibleOptions().add(item);
                        } else if (lexer.identifierEquals(FnvHash.Constants.DBPARTITION)) {
                            lexer.nextToken();
                            accept(Token.BY);
                            indexDefinition.setDbPartitionBy(primary());
                        } else if (lexer.identifierEquals(FnvHash.Constants.TBPARTITION)) {
                            lexer.nextToken();
                            accept(Token.BY);
                            SQLExpr expr = expr();
                            if (lexer.identifierEquals(FnvHash.Constants.STARTWITH)) {
                                lexer.nextToken();
                                SQLExpr start = primary();
                                acceptIdentifier("ENDWITH");
                                SQLExpr end = primary();
                                expr = new SQLBetweenExpr(expr, start, end);
                            }
                            indexDefinition.setTbPartitionBy(expr);
                        } else if (lexer.identifierEquals(FnvHash.Constants.TBPARTITIONS)) {
                            lexer.nextToken();
                            indexDefinition.setTbPartitions(primary());
                            //} else if (lexer.identifierEquals(FnvHash.Constants.GLOBAL)) {
                            //    lexer.nextToken();
                            //    indexDefinition.setGlobal(true);
                        } else {
                            break _opts;
                        }
                        break;
                    case PARTITION:
                        SQLPartitionBy partitionBy = new MySqlCreateTableParser(this).parsePartitionBy();
                        indexDefinition.setPartitioning(partitionBy);
                        break;
                    default:
                        break _opts;
                }
            }
        }
    }

    @Override
    protected SQLExpr parseSelectItemRest(String ident, long hash_lower) {
        SQLExpr expr = null;
        if (lexer.identifierEquals(FnvHash.Constants.COLLATE)
                && lexer.stringVal().charAt(0) != '`'
        ) {
            lexer.nextToken();
            String collate = lexer.stringVal();
            lexer.nextToken();

            SQLBinaryOpExpr binaryExpr = new SQLBinaryOpExpr(
                    new SQLIdentifierExpr(ident),
                    SQLBinaryOperator.COLLATE,
                    new SQLIdentifierExpr(collate), dbType
            );

            expr = binaryExpr;
        } else if (lexer.identifierEquals(FnvHash.Constants.REGEXP)
                && lexer.stringVal().charAt(0) != '`') {
            lexer.nextToken();
            SQLExpr rightExp = bitOr();

            SQLBinaryOpExpr binaryExpr = new SQLBinaryOpExpr(
                    new SQLIdentifierExpr(ident),
                    SQLBinaryOperator.RegExp,
                    rightExp, dbType
            );

            expr = binaryExpr;
            expr = relationalRest(expr);
        } else if (FnvHash.Constants.TIMESTAMP == hash_lower
                && lexer.stringVal().charAt(0) != '`'
                && lexer.token() == Token.LITERAL_CHARS) {
            String literal = lexer.stringVal();
            lexer.nextToken();

            SQLTimestampExpr ts = new SQLTimestampExpr(literal);
            expr = ts;

            if (lexer.identifierEquals(FnvHash.Constants.AT)) {
                Lexer.SavePoint mark = lexer.mark();
                lexer.nextToken();

                String timeZone = null;
                if (lexer.identifierEquals(FnvHash.Constants.TIME)) {
                    lexer.nextToken();
                    if (lexer.identifierEquals(FnvHash.Constants.ZONE)) {
                        lexer.nextToken();
                        timeZone = lexer.stringVal();
                        lexer.nextToken();
                    }
                }
                if (timeZone == null) {
                    lexer.reset(mark);
                } else {
                    ts.setTimeZone(timeZone);
                }
            }
        } else if (FnvHash.Constants.DATETIME == hash_lower
                && lexer.stringVal().charAt(0) != '`'
                && lexer.token() == Token.LITERAL_CHARS) {
            String literal = lexer.stringVal();
            lexer.nextToken();

            SQLDateTimeExpr ts = new SQLDateTimeExpr(literal);
            expr = ts;
        } else if (FnvHash.Constants.BOOLEAN == hash_lower
                && lexer.stringVal().charAt(0) != '`'
                && lexer.token() == Token.LITERAL_CHARS) {
            String literal = lexer.stringVal();
            lexer.nextToken();

            SQLBooleanExpr ts = new SQLBooleanExpr(Boolean.valueOf(literal));
            expr = ts;
        } else if ((FnvHash.Constants.CHAR == hash_lower || FnvHash.Constants.VARCHAR == hash_lower)
                && lexer.token() == Token.LITERAL_CHARS) {
            String literal = lexer.stringVal();
            lexer.nextToken();

            SQLCharExpr charExpr = new SQLCharExpr(literal);
            expr = charExpr;
        } else if (FnvHash.Constants.CURRENT_DATE == hash_lower
                && ident.charAt(0) != '`'
                && lexer.token() != Token.LPAREN) {
            expr = new SQLCurrentTimeExpr(SQLCurrentTimeExpr.Type.CURRENT_DATE);

        } else if (FnvHash.Constants.CURRENT_TIMESTAMP == hash_lower
                && ident.charAt(0) != '`'
                && lexer.token() != Token.LPAREN) {
            expr = new SQLCurrentTimeExpr(SQLCurrentTimeExpr.Type.CURRENT_TIMESTAMP);

        } else if (FnvHash.Constants.CURRENT_TIME == hash_lower
                && ident.charAt(0) != '`'
                && lexer.token() != Token.LPAREN) {
            expr = new SQLCurrentTimeExpr(SQLCurrentTimeExpr.Type.CURRENT_TIME);

        } else if (FnvHash.Constants.CURDATE == hash_lower
                && ident.charAt(0) != '`'
                && lexer.token() != Token.LPAREN) {
            expr = new SQLCurrentTimeExpr(SQLCurrentTimeExpr.Type.CURDATE);

        } else if (FnvHash.Constants.LOCALTIME == hash_lower
                && ident.charAt(0) != '`'
                && lexer.token() != Token.LPAREN) {
            expr = new SQLCurrentTimeExpr(SQLCurrentTimeExpr.Type.LOCALTIME);

        } else if (FnvHash.Constants.LOCALTIMESTAMP == hash_lower
                && ident.charAt(0) != '`'
                && lexer.token() != Token.LPAREN) {
            expr = new SQLCurrentTimeExpr(SQLCurrentTimeExpr.Type.LOCALTIMESTAMP);

        } else if ((FnvHash.Constants._LATIN1 == hash_lower)
                && ident.charAt(0) != '`'
        ) {
            String hexString;
            if (lexer.token() == Token.LITERAL_HEX) {
                hexString = lexer.hexString();
                lexer.nextToken();
            } else if (lexer.token() == Token.LITERAL_CHARS) {
                hexString = null;
            } else {
                acceptIdentifier("X");
                hexString = lexer.stringVal();
                accept(Token.LITERAL_CHARS);
            }

            if (hexString == null) {
                String str = lexer.stringVal();
                lexer.nextToken();

                String collate = null;
                if (lexer.identifierEquals(FnvHash.Constants.COLLATE)) {
                    lexer.nextToken();
                    collate = lexer.stringVal();
                    if (lexer.token() == Token.LITERAL_CHARS) {
                        lexer.nextToken();
                    } else {
                        accept(Token.IDENTIFIER);
                    }
                }

                expr = new MySqlCharExpr(str, "_latin1", collate);
            } else {
                expr = new MySqlCharExpr(hexString, "_latin1");
            }
        } else if ((FnvHash.Constants._UTF8 == hash_lower || FnvHash.Constants._UTF8MB4 == hash_lower)
                && ident.charAt(0) != '`'
        ) {
            String hexString;
            if (lexer.token() == Token.LITERAL_HEX) {
                hexString = lexer.hexString();
                lexer.nextToken();
            } else if (lexer.token() == Token.LITERAL_CHARS) {
                hexString = null;
            } else {
                acceptIdentifier("X");
                hexString = lexer.stringVal();
                accept(Token.LITERAL_CHARS);
            }

            if (hexString == null) {
                String str = lexer.stringVal();
                lexer.nextToken();

                String collate = null;
                if (lexer.identifierEquals(FnvHash.Constants.COLLATE)) {
                    lexer.nextToken();
                    collate = lexer.stringVal();
                    if (lexer.token() == Token.LITERAL_CHARS) {
                        lexer.nextToken();
                    } else {
                        accept(Token.IDENTIFIER);
                    }
                }

                expr = new MySqlCharExpr(str, "_utf8", collate);
            } else {
                expr = new SQLCharExpr(
                        MySqlUtils.utf8(hexString)
                );
            }
        } else if ((FnvHash.Constants._UTF16 == hash_lower || FnvHash.Constants._UCS2 == hash_lower)
                && ident.charAt(0) != '`'
        ) {
            String hexString;
            if (lexer.token() == Token.LITERAL_HEX) {
                hexString = lexer.hexString();
                lexer.nextToken();
            } else if (lexer.token() == Token.LITERAL_CHARS) {
                hexString = null;
            } else {
                acceptIdentifier("X");
                hexString = lexer.stringVal();
                accept(Token.LITERAL_CHARS);
            }

            if (hexString == null) {
                String str = lexer.stringVal();
                hexString = HexBin.encode(str.getBytes(MySqlUtils.ASCII));
                lexer.nextToken();
            }

            expr = new MySqlCharExpr(hexString, "_utf16");
        } else if (FnvHash.Constants._UTF32 == hash_lower
                && ident.charAt(0) != '`'
        ) {
            String hexString;
            if (lexer.token() == Token.LITERAL_HEX) {
                hexString = lexer.hexString();
                lexer.nextToken();
            } else if (lexer.token() == Token.LITERAL_CHARS) {
                hexString = null;
            } else {
                acceptIdentifier("X");
                hexString = lexer.stringVal();
                accept(Token.LITERAL_CHARS);
            }

            if (hexString == null) {
                String str = lexer.stringVal();
                lexer.nextToken();
                expr = new MySqlCharExpr(str, "_utf32");
            } else {
                expr = new SQLCharExpr(
                        MySqlUtils.utf32(hexString)
                );
            }
        } else if (FnvHash.Constants._GBK == hash_lower
                && ident.charAt(0) != '`'
        ) {
            String hexString;
            if (lexer.token() == Token.LITERAL_HEX) {
                hexString = lexer.hexString();
                lexer.nextToken();
            } else if (lexer.token() == Token.LITERAL_CHARS) {
                hexString = null;
            } else {
                acceptIdentifier("X");
                hexString = lexer.stringVal();
                accept(Token.LITERAL_CHARS);
            }

            if (hexString == null) {
                String str = lexer.stringVal();
                lexer.nextToken();
                expr = new MySqlCharExpr(str, "_gbk");
            } else {
                expr = new SQLCharExpr(
                        MySqlUtils.gbk(hexString)
                );
            }
        } else if (FnvHash.Constants._BIG5 == hash_lower
                && ident.charAt(0) != '`'
        ) {
            String hexString;
            if (lexer.token() == Token.LITERAL_HEX) {
                hexString = lexer.hexString();
                lexer.nextToken();
            } else if (lexer.token() == Token.LITERAL_CHARS) {
                hexString = null;
            } else {
                acceptIdentifier("X");
                hexString = lexer.stringVal();
                accept(Token.LITERAL_CHARS);
            }

            if (hexString == null) {
                String str = lexer.stringVal();
                lexer.nextToken();
                expr = new MySqlCharExpr(str, "_big5");
            } else {
                expr = new SQLCharExpr(
                        MySqlUtils.big5(hexString)
                );
            }
        }
        return expr;
    }

    protected SQLExpr parseSelectItemMethod(SQLExpr expr) {
        lexer.nextTokenValue();
        return this.methodRest(expr, false);
    }
    protected Pair<String, SQLExpr> parseSelectItemIdentifier(SQLExpr expr) {
        String as = null;
        if (lexer.hashLCase() == FnvHash.Constants.FORCE) {
            String force = lexer.stringVal();

            Lexer.SavePoint savePoint = lexer.mark();
            lexer.nextToken();

            if (lexer.token() == Token.PARTITION) {
                lexer.reset(savePoint);
                as = null;
            } else {
                as = force;
                if (isEnabled(SQLParserFeature.IgnoreNameQuotes) && as.length() > 1) {
                    as = StringUtils.removeNameQuotes(as);
                }
                lexer.nextTokenComma();
            }
        } else if (lexer.hashLCase() == FnvHash.Constants.SOUNDS) {
            String sounds = lexer.stringVal();

            Lexer.SavePoint savePoint = lexer.mark();
            lexer.nextToken();

            if (lexer.token() == Token.LIKE) {
                lexer.reset(savePoint);
                expr = exprRest(expr);
                as = as();
            } else {
                as = sounds;
                if (isEnabled(SQLParserFeature.IgnoreNameQuotes) && as.length() > 1) {
                    as = StringUtils.removeNameQuotes(as);
                }
                lexer.nextTokenComma();
            }
        } else if (lexer.hashLCase() == FnvHash.Constants.COLLATE
                && lexer.stringVal().charAt(0) != '`') {
            expr = primaryRest(expr);
            as = as();
        } else if (lexer.hashLCase() == FnvHash.Constants.REGEXP
                && lexer.stringVal().charAt(0) != '`') {
            expr = exprRest(expr);
            as = as();
        } else {
            as = lexer.stringVal();
            if (isEnabled(SQLParserFeature.IgnoreNameQuotes) && as.length() > 1) {
                as = StringUtils.removeNameQuotes(as);
            }
            lexer.nextTokenComma();
        }
        return Pair.of(as, expr);
    }

    @Override
    protected String parseSelectItemAlias(String alias) {
        boolean specialChar = false;
        for (int i = 0; i < alias.length(); ++i) {
            char ch = alias.charAt(i);
            if (ch == '`') {
                specialChar = true;
                break;
            }
        }
        if (specialChar) {
            alias = alias.replaceAll("`", "``");
            alias = '`' + alias + '`';
        }
        return alias;
    }
    public SQLExpr primary() {
        final Token tok = lexer.token();
        switch (tok) {
            case IDENTIFIER:
                final long hash_lower = lexer.hashLCase();
                Lexer.SavePoint savePoint = lexer.mark();

                if (hash_lower == FnvHash.Constants.OUTLINE) {
                    lexer.nextToken();
                    try {
                        SQLExpr file = primary();
                        SQLExpr expr = new MySqlOutFileExpr(file);

                        return primaryRest(expr);
                    } catch (ParserException e) {
                        lexer.reset(savePoint);
                    }
                }

                String strVal = lexer.stringVal();

                boolean quoteStart = strVal.length() > 0 && (strVal.charAt(0) == '`' || strVal.charAt(0) == '"');

                if (!quoteStart) {
                    // Allow function in order by when not start with '`'.
                    setAllowIdentifierMethod(true);
                }

                SQLCurrentTimeExpr currentTimeExpr = null;
                if (hash_lower == FnvHash.Constants.CURRENT_TIME && !quoteStart) {
                    currentTimeExpr = new SQLCurrentTimeExpr(SQLCurrentTimeExpr.Type.CURRENT_TIME);
                } else if (hash_lower == FnvHash.Constants.CURRENT_TIMESTAMP && !quoteStart) {
                    currentTimeExpr = new SQLCurrentTimeExpr(SQLCurrentTimeExpr.Type.CURRENT_TIMESTAMP);
                } else if (hash_lower == FnvHash.Constants.CURRENT_DATE && !quoteStart) {
                    currentTimeExpr = new SQLCurrentTimeExpr(SQLCurrentTimeExpr.Type.CURRENT_DATE);
                } else if (hash_lower == FnvHash.Constants.CURDATE && !quoteStart) {
                    currentTimeExpr = new SQLCurrentTimeExpr(SQLCurrentTimeExpr.Type.CURDATE);
                } else if (hash_lower == FnvHash.Constants.LOCALTIME && !quoteStart) {
                    currentTimeExpr = new SQLCurrentTimeExpr(SQLCurrentTimeExpr.Type.LOCALTIME);
                } else if (hash_lower == FnvHash.Constants.LOCALTIMESTAMP && !quoteStart) {
                    currentTimeExpr = new SQLCurrentTimeExpr(SQLCurrentTimeExpr.Type.LOCALTIMESTAMP);
                } else if (hash_lower == FnvHash.Constants.JSON_TABLE) {
                    if (lexer.identifierEquals("JSON_TABLE")) {
                        lexer.nextToken();
                        accept(Token.LPAREN);

                        MySqlJSONTableExpr jsonTable = new MySqlJSONTableExpr();
                        jsonTable.setExpr(
                                this.expr());
                        accept(Token.COMMA);
                        jsonTable.setPath(
                                this.expr());
                        acceptIdentifier("COLUMNS");
                        accept(Token.LPAREN);
                        for (; lexer.token() != Token.RPAREN; ) {
                            jsonTable.addColumn(
                                    parseJsonTableColumn());

                            if (lexer.token() == Token.COMMA) {
                                lexer.nextToken();
                                continue;
                            }
                            break;
                        }
                        accept(Token.RPAREN);

                        accept(Token.RPAREN);
                        return jsonTable;
                    }
                } else if ((hash_lower == FnvHash.Constants._LATIN1) && !quoteStart) {
                    lexer.nextToken();

                    String hexString;
                    if (lexer.identifierEquals(FnvHash.Constants.X)) {
                        lexer.nextToken();
                        hexString = lexer.stringVal();
                        lexer.nextToken();
                    } else if (lexer.token() == Token.LITERAL_CHARS) {
                        hexString = null;
                    } else {
                        hexString = lexer.hexString();
                        lexer.nextToken();
                    }

                    SQLExpr charExpr;
                    if (hexString == null) {
                        String str = lexer.stringVal();
                        lexer.nextToken();

                        String collate = null;
                        if (lexer.identifierEquals(FnvHash.Constants.COLLATE)) {
                            lexer.nextToken();
                            collate = lexer.stringVal();
                            if (lexer.token() == Token.LITERAL_CHARS) {
                                lexer.nextToken();
                            } else {
                                accept(Token.IDENTIFIER);
                            }
                        }

                        charExpr = new MySqlCharExpr(str, "_latin1", collate);
                    } else {
                        charExpr = new MySqlCharExpr(hexString, "_latin1");
                    }

                    return primaryRest(charExpr);
                } else if ((hash_lower == FnvHash.Constants._UTF8 || hash_lower == FnvHash.Constants._UTF8MB4)
                        && !quoteStart) {
                    lexer.nextToken();

                    String hexString;
                    if (lexer.identifierEquals(FnvHash.Constants.X)) {
                        lexer.nextToken();
                        hexString = lexer.stringVal();
                        lexer.nextToken();
                    } else if (lexer.token() == Token.LITERAL_CHARS) {
                        hexString = null;
                    } else {
                        hexString = lexer.hexString();
                        lexer.nextToken();
                    }

                    SQLExpr charExpr;
                    if (hexString == null) {
                        String str = lexer.stringVal();
                        lexer.nextToken();

                        String collate = null;
                        if (lexer.identifierEquals(FnvHash.Constants.COLLATE)) {
                            lexer.nextToken();
                            collate = lexer.stringVal();
                            if (lexer.token() == Token.LITERAL_CHARS) {
                                lexer.nextToken();
                            } else {
                                accept(Token.IDENTIFIER);
                            }
                        }
                        String charset = hash_lower == FnvHash.Constants._UTF8 ? "_utf8" : "_utf8mb4";
                        charExpr = new MySqlCharExpr(str, charset, collate);
                    } else {
                        String str = MySqlUtils.utf8(hexString);
                        charExpr = new SQLCharExpr(str);
                    }

                    return primaryRest(charExpr);
                } else if ((hash_lower == FnvHash.Constants._UTF16 || hash_lower == FnvHash.Constants._UCS2)
                        && !quoteStart) {
                    lexer.nextToken();

                    String hexString;
                    if (lexer.identifierEquals(FnvHash.Constants.X)) {
                        lexer.nextToken();
                        hexString = lexer.stringVal();
                        lexer.nextToken();
                    } else if (lexer.token() == Token.LITERAL_CHARS) {
                        hexString = null;
                    } else {
                        hexString = lexer.hexString();
                        lexer.nextToken();
                    }

                    SQLCharExpr charExpr;
                    if (hexString == null) {
                        String str = lexer.stringVal();
                        lexer.nextToken();
                        charExpr = new MySqlCharExpr(str, "_utf16");
                    } else {
                        charExpr = new SQLCharExpr(MySqlUtils.utf16(hexString));
                    }

                    return primaryRest(charExpr);
                } else if ((hash_lower == FnvHash.Constants._UTF16LE)
                        && !quoteStart) {
                    lexer.nextToken();

                    String hexString;
                    if (lexer.identifierEquals(FnvHash.Constants.X)) {
                        lexer.nextToken();
                        hexString = lexer.stringVal();
                        lexer.nextToken();
                    } else if (lexer.token() == Token.LITERAL_CHARS) {
                        hexString = null;
                    } else {
                        hexString = lexer.hexString();
                        lexer.nextToken();
                    }

                    SQLCharExpr charExpr;
                    if (hexString == null) {
                        String str = lexer.stringVal();
                        lexer.nextToken();
                        charExpr = new MySqlCharExpr(str, "_utf16le");
                    } else {
                        charExpr = new MySqlCharExpr(hexString, "_utf16le");
                    }

                    return primaryRest(charExpr);
                } else if (hash_lower == FnvHash.Constants._UTF32 && !quoteStart) {
                    lexer.nextToken();

                    String hexString;
                    if (lexer.identifierEquals(FnvHash.Constants.X)) {
                        lexer.nextToken();
                        hexString = lexer.stringVal();
                        lexer.nextToken();
                    } else if (lexer.token() == Token.LITERAL_CHARS) {
                        hexString = null;
                    } else {
                        hexString = lexer.hexString();
                        lexer.nextToken();
                    }

                    SQLCharExpr charExpr;
                    if (hexString == null) {
                        String str = lexer.stringVal();
                        lexer.nextToken();
                        charExpr = new MySqlCharExpr(str, "_utf32");
                    } else {
                        charExpr = new SQLCharExpr(MySqlUtils.utf32(hexString));
                    }

                    return primaryRest(charExpr);
                } else if (hash_lower == FnvHash.Constants._GBK && !quoteStart) {
                    lexer.nextToken();

                    String hexString;
                    if (lexer.identifierEquals(FnvHash.Constants.X)) {
                        lexer.nextToken();
                        hexString = lexer.stringVal();
                        lexer.nextToken();
                    } else if (lexer.token() == Token.LITERAL_CHARS) {
                        hexString = null;
                    } else {
                        hexString = lexer.hexString();
                        lexer.nextToken();
                    }

                    SQLCharExpr charExpr;
                    if (hexString == null) {
                        String str = lexer.stringVal();
                        lexer.nextToken();
                        charExpr = new MySqlCharExpr(str, "_gbk");
                    } else {
                        charExpr = new SQLCharExpr(MySqlUtils.gbk(hexString));
                    }

                    return primaryRest(charExpr);
                } else if (hash_lower == FnvHash.Constants._UJIS && !quoteStart) {
                    lexer.nextToken();

                    String hexString;
                    if (lexer.identifierEquals(FnvHash.Constants.X)) {
                        lexer.nextToken();
                        hexString = lexer.stringVal();
                        lexer.nextToken();
                    } else if (lexer.token() == Token.LITERAL_CHARS) {
                        hexString = null;
                    } else {
                        hexString = lexer.hexString();
                        lexer.nextToken();
                    }

                    SQLCharExpr charExpr;
                    if (hexString == null) {
                        String str = lexer.stringVal();
                        lexer.nextToken();
                        charExpr = new MySqlCharExpr(str, "_ujis");
                    } else {
                        charExpr = new MySqlCharExpr(hexString, "_ujis");
                    }

                    return primaryRest(charExpr);
                } else if (hash_lower == FnvHash.Constants._BIG5 && !quoteStart) {
                    lexer.nextToken();

                    String hexString;
                    if (lexer.identifierEquals(FnvHash.Constants.X)) {
                        lexer.nextToken();
                        hexString = lexer.stringVal();
                        lexer.nextToken();
                    } else if (lexer.token() == Token.LITERAL_CHARS) {
                        hexString = null;
                    } else {
                        hexString = lexer.hexString();
                        lexer.nextToken();
                    }

                    SQLCharExpr charExpr;
                    if (hexString == null) {
                        String str = lexer.stringVal();
                        lexer.nextToken();
                        charExpr = new MySqlCharExpr(str, "_big5");
                    } else {
                        charExpr = new SQLCharExpr(MySqlUtils.big5(hexString));
                    }

                    return primaryRest(charExpr);
                } else if (hash_lower == FnvHash.Constants.CURRENT_USER && isEnabled(SQLParserFeature.EnableCurrentUserExpr)) {
                    lexer.nextToken();
                    return primaryRest(
                            new SQLCurrentUserExpr());
                } else if (hash_lower == -5808529385363204345L && lexer.charAt(lexer.pos()) == '\'') { // hex
                    lexer.nextToken();
                    SQLHexExpr hex = new SQLHexExpr(lexer.stringVal());
                    lexer.nextToken();
                    return primaryRest(hex);
                }

                if (currentTimeExpr != null) {
                    String methodName = lexer.stringVal();
                    lexer.nextToken();

                    if (lexer.token() == Token.LPAREN) {
                        lexer.nextToken();
                        if (lexer.token() == Token.LPAREN) {
                            lexer.nextToken();
                        } else {
                            return primaryRest(
                                    methodRest(new SQLIdentifierExpr(methodName), false)
                            );
                        }
                    }

                    return primaryRest(currentTimeExpr);
                }

                return super.primary();
            case VARIANT:
                SQLVariantRefExpr varRefExpr = new SQLVariantRefExpr(lexer.stringVal());
                lexer.nextToken();
                if (varRefExpr.getName().equalsIgnoreCase("@@global")) {
                    accept(Token.DOT);
                    varRefExpr = new SQLVariantRefExpr(lexer.stringVal(), true);
                    lexer.nextToken();
                } else if (varRefExpr.getName().equals("@") && lexer.token() == Token.LITERAL_CHARS) {
                    varRefExpr.setName("@'" + lexer.stringVal() + "'");
                    lexer.nextToken();
                } else if (varRefExpr.getName().equals("@@") && lexer.token() == Token.LITERAL_CHARS) {
                    varRefExpr.setName("@@'" + lexer.stringVal() + "'");
                    lexer.nextToken();
                }
                return primaryRest(varRefExpr);
            case VALUES:
                lexer.nextToken();

                if (lexer.token() != Token.LPAREN) {
                    SQLExpr expr = primary();
                    SQLValuesQuery values = new SQLValuesQuery();
                    values.addValue(new SQLListExpr(expr));
                    return new SQLQueryExpr(new SQLSelect(values));
                }
                return this.methodRest(new SQLIdentifierExpr("VALUES"), true);
            case BINARY:
                lexer.nextToken();
                if (lexer.token() == Token.COMMA || lexer.token() == Token.SEMI || lexer.token() == Token.EOF) {
                    return new SQLIdentifierExpr("BINARY");
                } else {
                    SQLUnaryExpr binaryExpr = new SQLUnaryExpr(SQLUnaryOperator.BINARY, primary());
                    return primaryRest(binaryExpr);
                }
            default:
                if (lexer.token() == Token.WITH) {
                    SQLQueryExpr queryExpr = new SQLQueryExpr(
                        createSelectParser()
                            .select());
                    return queryExpr;
                }

                return super.primary();
        }

    }

    protected MySqlJSONTableExpr.Column parseJsonTableColumn() {
        MySqlJSONTableExpr.Column column = new MySqlJSONTableExpr.Column();

        SQLName name = this.name();
        column.setName(
                name);

        if (lexer.token() == Token.FOR) {
            lexer.nextToken();
            acceptIdentifier("ORDINALITY");
            column.setOrdinality(true);
        } else {
            boolean nested = name instanceof SQLIdentifierExpr
                    && name.nameHashCode64() == FnvHash.Constants.NESTED;

            if (!nested) {
                column.setDataType(
                        this.parseDataType());
            }

            if (lexer.token() == Token.EXISTS) {
                lexer.nextToken();
                column.setExists(true);
            }

            if (lexer.identifierEquals(FnvHash.Constants.PATH)) {
                lexer.nextToken();
                column.setPath(
                        this.primary());
            }

            if (name instanceof SQLIdentifierExpr
                    && name.nameHashCode64() == FnvHash.Constants.NESTED) {
                acceptIdentifier("COLUMNS");
                accept(Token.LPAREN);
                for (; lexer.token() != Token.RPAREN; ) {
                    MySqlJSONTableExpr.Column nestedColumn = parseJsonTableColumn();
                    column.addNestedColumn(nestedColumn);

                    if (lexer.token() == Token.COMMA) {
                        lexer.nextToken();
                        continue;
                    }
                    break;
                }
                accept(Token.RPAREN);
            }

            for (int i = 0; i < 2; ++i) {
                if (lexer.identifierEquals("ERROR")
                        || lexer.token() == Token.DEFAULT
                        || lexer.token() == Token.NULL) {
                    if (lexer.token() == Token.DEFAULT) {
                        lexer.nextToken();
                    }

                    SQLExpr expr = this.expr();
                    accept(Token.ON);
                    if (lexer.identifierEquals("ERROR")) {
                        lexer.nextToken();
                        column.setOnError(expr);
                    } else {
                        acceptIdentifier("EMPTY");
                        column.setOnEmpty(expr);
                    }
                }
            }
        }

        return column;
    }

    public final SQLExpr primaryRest(SQLExpr expr) {
        if (expr == null) {
            throw new IllegalArgumentException("expr");
        }

        if (lexer.token() == Token.LITERAL_CHARS) {
            if (expr instanceof SQLIdentifierExpr) {
                SQLIdentifierExpr identExpr = (SQLIdentifierExpr) expr;
                String ident = identExpr.getName();

                if (ident.equalsIgnoreCase("x")) {
                    char ch = lexer.charAt(lexer.pos());
                    if (ch == '\'') {
                        String charValue = lexer.stringVal();
                        lexer.nextToken();
                        expr = new SQLHexExpr(charValue);
                        return primaryRest(expr);
                    }

//                } else if (ident.equalsIgnoreCase("b")) {
//                    String charValue = lexer.stringVal();
//                    lexer.nextToken();
//                    expr = new SQLBinaryExpr(charValue);
//
//                    return primaryRest(expr);
                } else if (ident.startsWith("_")) {
                    String charValue = lexer.stringVal();
                    lexer.nextToken();

                    MySqlCharExpr mysqlCharExpr = new MySqlCharExpr(charValue);
                    mysqlCharExpr.setCharset(identExpr.getName());
                    if (lexer.identifierEquals(FnvHash.Constants.COLLATE)) {
                        lexer.nextToken();

                        String collate = lexer.stringVal();
                        mysqlCharExpr.setCollate(collate);
                        if (lexer.token() == Token.LITERAL_CHARS) {
                            lexer.nextToken();
                        } else {
                            accept(Token.IDENTIFIER);
                        }
                    }

                    expr = mysqlCharExpr;

                    return primaryRest(expr);
                }
            } else if (expr instanceof SQLCharExpr) {
                StringBuilder text2 = new StringBuilder(((SQLCharExpr) expr).getText());
                do {
                    String chars = lexer.stringVal();
                    text2.append(chars);
                    lexer.nextToken();
                } while (lexer.token() == Token.LITERAL_CHARS || lexer.token() == Token.LITERAL_ALIAS);
                expr = new SQLCharExpr(text2.toString());
            } else if (expr instanceof SQLVariantRefExpr) {
                SQLMethodInvokeExpr concat = new SQLMethodInvokeExpr("CONCAT");
                concat.addArgument(expr);
                concat.addArgument(this.primary());
                expr = concat;

                return primaryRest(expr);
            }
        } else if (lexer.token() == Token.IDENTIFIER) {
            if (expr instanceof SQLHexExpr) {
                if ("USING".equalsIgnoreCase(lexer.stringVal())) {
                    lexer.nextToken();
                    if (lexer.token() != Token.IDENTIFIER) {
                        throw new ParserException("syntax error, illegal hex. " + lexer.info());
                    }
                    String charSet = lexer.stringVal();
                    lexer.nextToken();
                    expr.getAttributes().put("USING", charSet);

                    return primaryRest(expr);
                }
            } else if (lexer.identifierEquals(FnvHash.Constants.COLLATE)) {
                lexer.nextToken();

                if (lexer.token() == Token.EQ) {
                    lexer.nextToken();
                }

                if (lexer.token() != Token.IDENTIFIER
                        && lexer.token() != Token.LITERAL_CHARS) {
                    throw new ParserException("syntax error. " + lexer.info());
                }

                String collate = lexer.stringVal();
                lexer.nextToken();

                SQLBinaryOpExpr binaryExpr = new SQLBinaryOpExpr(expr, SQLBinaryOperator.COLLATE,
                        new SQLIdentifierExpr(collate), DbType.mysql);

                expr = binaryExpr;

                return primaryRest(expr);
            } else if (expr instanceof SQLVariantRefExpr) {
                if (lexer.identifierEquals(FnvHash.Constants.COLLATE)) {
                    lexer.nextToken();

                    if (lexer.token() != Token.IDENTIFIER
                            && lexer.token() != Token.LITERAL_CHARS) {
                        throw new ParserException("syntax error. " + lexer.info());
                    }

                    String collate = lexer.stringVal();
                    lexer.nextToken();

                    expr.putAttribute("COLLATE", collate);

                    return primaryRest(expr);
                }
            }
        } else if (lexer.token() == Token.LBRACKET) {
            SQLArrayExpr array = new SQLArrayExpr();
            array.setExpr(expr);
            lexer.nextToken();
            this.exprList(array.getValues(), array);
            accept(Token.RBRACKET);
            return primaryRest(array);
        }

//        if (lexer.token() == Token.LPAREN && expr instanceof SQLIdentifierExpr) {
//            SQLIdentifierExpr identExpr = (SQLIdentifierExpr) expr;
//            String ident = identExpr.getName();
//
//            if ("POSITION".equalsIgnoreCase(ident)) {
//                return parsePosition();
//            }
//        }

        if (lexer.token() == Token.VARIANT) {
            String variant = lexer.stringVal();
            if ("@".equals(variant)) {
                return userNameRest(expr);
            } else if ("@localhost".equals(variant)) {
                return userNameRest(expr);
            } else {
                throw new ParserException("syntax error. " + lexer.info());
            }
        }

        if (lexer.token() == Token.ERROR) {
            throw new ParserException("syntax error. " + lexer.info());
        }

        return super.primaryRest(expr);
    }

    public SQLName userName() {
        SQLName name = this.name();
        if (lexer.token() == Token.LPAREN && name.hashCode64() == FnvHash.Constants.CURRENT_USER) {
            lexer.nextToken();
            accept(Token.RPAREN);
            return name;
        }

        return (SQLName) userNameRest(name);
    }

    private SQLExpr userNameRest(SQLExpr expr) {
        if (lexer.token() != Token.VARIANT || !lexer.stringVal().startsWith("@")) {
            return expr;
        }

        MySqlUserName userName = new MySqlUserName();
        if (expr instanceof SQLCharExpr) {
            userName.setUserName(((SQLCharExpr) expr).getText());
        } else {
            userName.setUserName(((SQLIdentifierExpr) expr).getName());
        }

        String strVal = lexer.stringVal();
        lexer.nextToken();

        if (strVal.length() > 1) {
            userName.setHost(strVal.substring(1));
            return userName;
        }

        if (lexer.token() == Token.LITERAL_CHARS) {
            userName.setHost(lexer.stringVal());
        } else {
            if (lexer.token() == Token.PERCENT) {
                throw new ParserException("syntax error. " + lexer.info());
            } else {
                userName.setHost(lexer.stringVal());
            }
        }
        lexer.nextToken();

        if (lexer.identifierEquals(FnvHash.Constants.IDENTIFIED)) {
            Lexer.SavePoint mark = lexer.mark();

            lexer.nextToken();
            if (lexer.token() == Token.BY) {
                lexer.nextToken();
                if (lexer.identifierEquals(FnvHash.Constants.PASSWORD)) {
                    lexer.reset(mark);
                } else {
                    userName.setIdentifiedBy(lexer.stringVal());
                    lexer.nextToken();
                }
            } else {
                lexer.reset(mark);
            }
        }

        return userName;
    }

    protected SQLExpr parsePosition() {
        SQLExpr expr = this.primary();
        expr = this.primaryRest(expr);
        expr = bitXorRest(expr);
        expr = additiveRest(expr);
        expr = shiftRest(expr);
        expr = bitAndRest(expr);
        expr = bitOrRest(expr);

        if (lexer.token() == Token.IN) {
            accept(Token.IN);
        } else if (lexer.token() == Token.COMMA) {
            accept(Token.COMMA);
        } else {
            throw new ParserException("syntax error. " + lexer.info());
        }
        SQLExpr str = this.expr();
        accept(Token.RPAREN);

        SQLMethodInvokeExpr locate = new SQLMethodInvokeExpr("LOCATE");
        locate.addArgument(expr);
        locate.addArgument(str);

        return primaryRest(locate);
    }

    protected SQLExpr parseExtract() {
        SQLExpr expr;
        if (lexer.token() != Token.IDENTIFIER) {
            throw new ParserException("syntax error. " + lexer.info());
        }

        String unitVal = lexer.stringVal();
        SQLIntervalUnit unit = SQLIntervalUnit.valueOf(unitVal.toUpperCase());
        lexer.nextToken();

        accept(Token.FROM);

        SQLExpr value = expr();

        SQLExtractExpr extract = new SQLExtractExpr();
        extract.setValue(value);
        extract.setUnit(unit);
        accept(Token.RPAREN);

        expr = extract;

        return primaryRest(expr);
    }

    public SQLSelectParser createSelectParser() {
        return new MySqlSelectParser(this);
    }

    protected SQLExpr parseInterval() {
        accept(Token.INTERVAL);

        if (lexer.token() == Token.LPAREN) {
            lexer.nextToken();

            SQLMethodInvokeExpr methodInvokeExpr = new SQLMethodInvokeExpr("INTERVAL");
            if (lexer.token() != Token.RPAREN) {
                exprList(methodInvokeExpr.getArguments(), methodInvokeExpr);
            }

            accept(Token.RPAREN);

            if (methodInvokeExpr.getArguments().size() == 1
                    && lexer.token() == Token.IDENTIFIER) {
                SQLExpr value = methodInvokeExpr.getArguments().get(0);
                String unit = lexer.stringVal();
                lexer.nextToken();

                SQLIntervalExpr intervalExpr = new SQLIntervalExpr();
                intervalExpr.setValue(value);
                intervalExpr.setUnit(SQLIntervalUnit.valueOf(unit.toUpperCase()));
                return intervalExpr;
            } else {
                return primaryRest(methodInvokeExpr);
            }
        } else {
            SQLExpr value = expr();

            if (lexer.token() != Token.IDENTIFIER) {
                throw new ParserException("Syntax error. " + lexer.info());
            }

            SQLIntervalUnit intervalUnit = null;

            String unit = lexer.stringVal();
            long unitHash = lexer.hashLCase();
            lexer.nextToken();

            intervalUnit = SQLIntervalUnit.valueOf(unit.toUpperCase());
            if (lexer.token() == Token.TO) {
                lexer.nextToken();
                if (unitHash == FnvHash.Constants.YEAR) {
                    if (lexer.identifierEquals(FnvHash.Constants.MONTH)) {
                        lexer.nextToken();
                        intervalUnit = SQLIntervalUnit.YEAR_MONTH;
                    } else {
                        throw new ParserException("Syntax error. " + lexer.info());
                    }
                } else if (unitHash == FnvHash.Constants.DAY) {
                    if (lexer.identifierEquals(FnvHash.Constants.HOUR)) {
                        lexer.nextToken();
                        intervalUnit = SQLIntervalUnit.DAY_HOUR;
                    } else if (lexer.identifierEquals(FnvHash.Constants.MINUTE)) {
                        lexer.nextToken();
                        intervalUnit = SQLIntervalUnit.DAY_MINUTE;
                    } else if (lexer.identifierEquals(FnvHash.Constants.SECOND)) {
                        lexer.nextToken();
                        intervalUnit = SQLIntervalUnit.DAY_SECOND;
                    } else if (lexer.identifierEquals(FnvHash.Constants.MICROSECOND)) {
                        lexer.nextToken();
                        intervalUnit = SQLIntervalUnit.DAY_MICROSECOND;
                    } else {
                        throw new ParserException("Syntax error. " + lexer.info());
                    }
                } else if (unitHash == FnvHash.Constants.HOUR) {
                    if (lexer.identifierEquals(FnvHash.Constants.MINUTE)) {
                        lexer.nextToken();
                        intervalUnit = SQLIntervalUnit.HOUR_MINUTE;
                    } else if (lexer.identifierEquals(FnvHash.Constants.SECOND)) {
                        lexer.nextToken();
                        intervalUnit = SQLIntervalUnit.HOUR_SECOND;
                    } else if (lexer.identifierEquals(FnvHash.Constants.MICROSECOND)) {
                        lexer.nextToken();
                        intervalUnit = SQLIntervalUnit.HOUR_MICROSECOND;
                    } else {
                        throw new ParserException("Syntax error. " + lexer.info());
                    }
                } else if (unitHash == FnvHash.Constants.MINUTE) {
                    if (lexer.identifierEquals(FnvHash.Constants.SECOND)) {
                        lexer.nextToken();
                        intervalUnit = SQLIntervalUnit.MINUTE_SECOND;
                    } else if (lexer.identifierEquals(FnvHash.Constants.MICROSECOND)) {
                        lexer.nextToken();
                        intervalUnit = SQLIntervalUnit.MINUTE_MICROSECOND;
                    } else {
                        throw new ParserException("Syntax error. " + lexer.info());
                    }
                } else if (unitHash == FnvHash.Constants.SECOND) {
                    if (lexer.identifierEquals(FnvHash.Constants.MICROSECOND)) {
                        lexer.nextToken();
                        intervalUnit = SQLIntervalUnit.SECOND_MICROSECOND;
                    } else {
                        throw new ParserException("Syntax error. " + lexer.info());
                    }
                } else {
                    throw new ParserException("Syntax error. " + lexer.info());
                }
            }

            SQLIntervalExpr intervalExpr = new SQLIntervalExpr();
            intervalExpr.setValue(value);
            intervalExpr.setUnit(intervalUnit);

            return intervalExpr;
        }
    }

    public SQLColumnDefinition parseColumn() {
        SQLColumnDefinition column = new SQLColumnDefinition();
        column.setDbType(dbType);

        SQLName name = name();
        column.setName(name);
        column.setDataType(
                parseDataType());

        if (column.getDataType() != null && column.getDataType().jdbcType() == Types.CHAR) {
            // ENUM or SET with character set.
            if (lexer.identifierEquals(FnvHash.Constants.CHARACTER)) {
                lexer.nextToken();

                accept(Token.SET);

                if (lexer.token() != Token.IDENTIFIER
                        && lexer.token() != Token.LITERAL_CHARS) {
                    throw new ParserException(lexer.info());
                }
                column.setCharsetExpr(primary());
            }
        }

        // May multiple collate caused by type with collate.
        while (lexer.identifierEquals(FnvHash.Constants.COLLATE)) {
            lexer.nextToken();
            SQLExpr collateExpr;
            if (lexer.token() == Token.IDENTIFIER) {
                collateExpr = new SQLIdentifierExpr(lexer.stringVal());
            } else {
                collateExpr = new SQLCharExpr(lexer.stringVal());
            }
            lexer.nextToken();
            column.setCollateExpr(collateExpr);
        }

        if (lexer.identifierEquals(FnvHash.Constants.GENERATED)) {
            lexer.nextToken();
            acceptIdentifier("ALWAYS");
            accept(Token.AS);
            accept(Token.LPAREN);
            SQLExpr expr = this.expr();
            accept(Token.RPAREN);
            column.setGeneratedAlwaysAs(expr);
        }

        return parseColumnRest(column);
    }

    public SQLColumnDefinition parseColumnRest(SQLColumnDefinition column) {
        if (lexer.token() == Token.ON) {
            lexer.nextToken();
            accept(Token.UPDATE);
            SQLExpr expr = this.primary();
            column.setOnUpdate(expr);
        }

        if (lexer.identifierEquals(FnvHash.Constants.ENCODE)) {
            lexer.nextToken();
            accept(Token.EQ);
            column.setEncode(this.charExpr());
        }
        if (lexer.identifierEquals(FnvHash.Constants.COMPRESSION)) {
            lexer.nextToken();
            accept(Token.EQ);
            column.setCompression(this.charExpr());
        }

        if (lexer.identifierEquals(FnvHash.Constants.CHARACTER)
                || lexer.identifierEquals(FnvHash.Constants.CHARSET)) {
            if (lexer.identifierEquals(FnvHash.Constants.CHARACTER)) {
                lexer.nextToken();
                accept(Token.SET);
            } else {
                lexer.nextToken();
            }

            SQLExpr charSet;
            if (lexer.token() == Token.IDENTIFIER) {
                charSet = new SQLIdentifierExpr(lexer.stringVal());
            } else {
                charSet = new SQLCharExpr(lexer.stringVal());
            }
            lexer.nextToken();
            column.setCharsetExpr(charSet);

            return parseColumnRest(column);
        }
        if (lexer.identifierEquals("disableindex")) {
            lexer.nextToken();
            if (lexer.token() == Token.TRUE) {
                lexer.nextToken();
                column.setDisableIndex(true);
            }
            return parseColumnRest(column);
        }
        if (lexer.identifierEquals("jsonIndexAttrs")) {
            lexer.nextToken();
            column.setJsonIndexAttrsExpr(new SQLIdentifierExpr(lexer.stringVal()));
            lexer.nextToken();
            return parseColumnRest(column);
        }
        if (lexer.identifierEquals("precision")) {
            lexer.nextToken();
            int precision = parseIntValue();
            acceptIdentifier("scale");
            int scale = parseIntValue();

            List<SQLExpr> arguments = column.getDataType().getArguments();
            arguments.add(new SQLIntegerExpr(precision));
            arguments.add(new SQLIntegerExpr(scale));

            return parseColumnRest(column);
        }
        if (lexer.identifierEquals(FnvHash.Constants.COLLATE)) {
            lexer.nextToken();
            SQLExpr collateExpr;
            if (lexer.token() == Token.IDENTIFIER) {
                collateExpr = new SQLIdentifierExpr(lexer.stringVal());
            } else {
                collateExpr = new SQLCharExpr(lexer.stringVal());
            }
            lexer.nextToken();
            column.setCollateExpr(collateExpr);
            return parseColumnRest(column);
        }

        if (lexer.identifierEquals(FnvHash.Constants.PRECISION)
                && column.getDataType().nameHashCode64() == FnvHash.Constants.DOUBLE) {
            lexer.nextToken();
        }

        /* Allow partition in alter table.
        if (lexer.token() == Token.PARTITION) {
            throw new ParserException("syntax error " + lexer.info());
        }
        */

        if (lexer.identifierEquals("COLUMN_FORMAT")) {
            lexer.nextToken();
            SQLExpr expr = expr();
            column.setFormat(expr);
        }

        if (lexer.identifierEquals(FnvHash.Constants.STORAGE)) {
            lexer.nextToken();
            SQLExpr expr = expr();
            column.setStorage(expr);
        }

        if (lexer.token() == Token.AS) {
            lexer.nextToken();
            accept(Token.LPAREN);
            SQLExpr expr = expr();
            column.setAsExpr(expr);
            accept(Token.RPAREN);
        }

        if (lexer.identifierEquals(FnvHash.Constants.STORED) || lexer.identifierEquals("PERSISTENT")) {
            lexer.nextToken();
            column.setStored(true);
        }

        if (lexer.identifierEquals(FnvHash.Constants.VIRTUAL)) {
            lexer.nextToken();
            column.setVirtual(true);
        }

        if (lexer.identifierEquals(FnvHash.Constants.DELIMITER)) {
            lexer.nextToken();
            SQLExpr expr = this.expr();
            column.setDelimiter(expr);
            return parseColumnRest(column);
        }

        if (lexer.identifierEquals("delimiter_tokenizer")) {
            lexer.nextToken();
            SQLExpr expr = this.expr();
            column.setDelimiterTokenizer(expr);
            return parseColumnRest(column);
        }

        if (lexer.identifierEquals("nlp_tokenizer")) {
            lexer.nextToken();
            SQLExpr expr = this.expr();
            column.setNlpTokenizer(expr);
        }

        if (lexer.identifierEquals("value_type")) {
            lexer.nextToken();
            SQLExpr expr = this.expr();
            column.setValueType(expr);
        }

        if (lexer.identifierEquals(FnvHash.Constants.COLPROPERTIES)) {
            lexer.nextToken();
            this.parseAssignItem(column.getColProperties(), column);
        }

        if (lexer.identifierEquals(FnvHash.Constants.ANNINDEX)) {
            lexer.nextToken();

            accept(Token.LPAREN);
            SQLAnnIndex annIndex = new SQLAnnIndex();
            for (; ; ) {
                if (lexer.identifierEquals(FnvHash.Constants.TYPE)) {
                    lexer.nextToken();
                    accept(Token.EQ);
                    String type = lexer.stringVal();
                    annIndex.setIndexType(type);
                    accept(Token.LITERAL_CHARS);
                } else if (lexer.identifierEquals(FnvHash.Constants.RTTYPE)) {
                    lexer.nextToken();
                    accept(Token.EQ);
                    String type = lexer.stringVal();
                    annIndex.setRtIndexType(type);
                    accept(Token.LITERAL_CHARS);
                } else if (lexer.identifierEquals(FnvHash.Constants.DISTANCE)) {
                    lexer.nextToken();
                    accept(Token.EQ);
                    String type = lexer.stringVal();
                    annIndex.setDistance(type);
                    accept(Token.LITERAL_CHARS);
                }

                if (lexer.token() == Token.COMMA) {
                    lexer.nextToken();
                    continue;
                }

                break;
            }

            accept(Token.RPAREN);

            column.setAnnIndex(annIndex);

            return parseColumnRest(column);
        }

        super.parseColumnRest(column);

        return column;
    }

    protected SQLDataType parseDataTypeRest(SQLDataType dataType) {
        super.parseDataTypeRest(dataType);

        for (; ; ) {
            if (lexer.identifierEquals(FnvHash.Constants.UNSIGNED)) {
                lexer.nextToken();
                ((SQLDataTypeImpl) dataType).setUnsigned(true);
            } else if (lexer.identifierEquals(FnvHash.Constants.SIGNED)) {
                lexer.nextToken(); // skip
            } else if (lexer.identifierEquals(FnvHash.Constants.ZEROFILL)) {
                lexer.nextToken();
                ((SQLDataTypeImpl) dataType).setZerofill(true);
            } else {
                break;
            }
        }

        if (lexer.identifierEquals(FnvHash.Constants.ARRAY)) {
            lexer.nextToken();
            dataType = new SQLArrayDataType(dataType);
        }

        return dataType;
    }

    public SQLAssignItem parseAssignItem(boolean variant, SQLObject parent) {
        SQLAssignItem item = new SQLAssignItem();

        SQLExpr var = primary();

        String ident = null;
        long identHash = 0;
        if (variant && var instanceof SQLIdentifierExpr) {
            SQLIdentifierExpr identExpr = (SQLIdentifierExpr) var;
            ident = identExpr.getName();
            identHash = identExpr.hashCode64();

            if (identHash == FnvHash.Constants.GLOBAL) {
                ident = lexer.stringVal();
                lexer.nextToken();
                var = new SQLVariantRefExpr(ident, true);
            } else if (identHash == FnvHash.Constants.SESSION) {
                ident = lexer.stringVal();
                lexer.nextToken();
                var = new SQLVariantRefExpr(ident, false, true);
            } else {
                var = new SQLVariantRefExpr(ident);
            }
        }

        if (identHash == FnvHash.Constants.NAMES) {
            String charset = lexer.stringVal();

            SQLExpr varExpr = null;
            boolean chars = false;
            final Token token = lexer.token();
            if (token == Token.IDENTIFIER) {
                lexer.nextToken();
            } else if (token == Token.DEFAULT) {
                charset = "DEFAULT";
                lexer.nextToken();
            } else if (token == Token.QUES) {
                varExpr = new SQLVariantRefExpr("?");
                lexer.nextToken();
            } else {
                chars = true;
                accept(Token.LITERAL_CHARS);
            }

            if (lexer.identifierEquals(FnvHash.Constants.COLLATE)) {
                MySqlCharExpr charsetExpr = new MySqlCharExpr(charset);
                lexer.nextToken();

                String collate = lexer.stringVal();
                lexer.nextToken();
                charsetExpr.setCollate(collate);

                item.setValue(charsetExpr);
            } else {
                if (varExpr != null) {
                    item.setValue(varExpr);
                } else {
                    item.setValue(chars
                            ? new SQLCharExpr(charset)
                            : new SQLIdentifierExpr(charset)
                    );
                }
            }

            item.setTarget(var);
            return item;
        } else if (identHash == FnvHash.Constants.CHARACTER) {
            var = new SQLVariantRefExpr("CHARACTER SET");
            accept(Token.SET);
            if (lexer.token() == Token.EQ) {
                lexer.nextToken();
            }
        } else if (identHash == FnvHash.Constants.CHARSET) {
            var = new SQLVariantRefExpr("CHARACTER SET");
            if (lexer.token() == Token.EQ) {
                lexer.nextToken();
            }
        } else if (identHash == FnvHash.Constants.TRANSACTION) {
            var = new SQLVariantRefExpr("TRANSACTION");
            if (lexer.token() == Token.EQ) {
                lexer.nextToken();
            }
        } else {
            if (lexer.token() == Token.COLONEQ) {
                lexer.nextToken();
            } else {
                accept(Token.EQ);
            }
        }

        if (lexer.token() == Token.ON) {
            lexer.nextToken();
            item.setValue(new SQLIdentifierExpr("ON"));
        } else {
            item.setValue(this.expr());
        }

        item.setTarget(var);
        return item;
    }

    public SQLName nameRest(SQLName name) {
        if (lexer.token() == Token.VARIANT && "@".equals(lexer.stringVal())) {
            lexer.nextToken();
            MySqlUserName userName = new MySqlUserName();
            userName.setUserName(((SQLIdentifierExpr) name).getName());

            if (lexer.token() == Token.LITERAL_CHARS) {
                userName.setHost("'" + lexer.stringVal() + "'");
            } else {
                userName.setHost(lexer.stringVal());
            }
            lexer.nextToken();

            if (lexer.identifierEquals(FnvHash.Constants.IDENTIFIED)) {
                lexer.nextToken();
                accept(Token.BY);
                userName.setIdentifiedBy(lexer.stringVal());
                lexer.nextToken();
            }

            return userName;
        }
        return super.nameRest(name);
    }

    @Override
    public MySqlPrimaryKey parsePrimaryKey() {
        MySqlPrimaryKey primaryKey = new MySqlPrimaryKey();
        parseIndex(primaryKey.getIndexDefinition());
        return primaryKey;
        /*
        accept(Token.PRIMARY);
        accept(Token.KEY);

        MySqlPrimaryKey primaryKey = new MySqlPrimaryKey();

        if (lexer.identifierEquals(FnvHash.Constants.USING)) {
            lexer.nextToken();
            primaryKey.setIndexType(lexer.stringVal());
            lexer.nextToken();
        }

        if (lexer.token() != Token.LPAREN) {
            SQLName name = this.name();
            primaryKey.setName(name);
        }

        accept(Token.LPAREN);
        for (;;) {
            setAllowIdentifierMethod(false);

            SQLExpr expr;
            if (lexer.token() == Token.LITERAL_ALIAS) {
                expr = this.name();
            } else {
                expr = this.expr();
            }

            setAllowIdentifierMethod(true);

            SQLSelectOrderByItem item = new SQLSelectOrderByItem();

            item.setExpr(expr);

            if (lexer.token() == Token.ASC) {
                lexer.nextToken();
                item.setType(SQLOrderingSpecification.ASC);
            } else if (lexer.token() == Token.DESC) {
                lexer.nextToken();
                item.setType(SQLOrderingSpecification.DESC);
            }

            primaryKey.addColumn(item);
            if (!(lexer.token() == (Token.COMMA))) {
                break;
            } else {
                lexer.nextToken();
            }
        }
        accept(Token.RPAREN);

        for (;;) {
            if (lexer.token() == Token.COMMENT) {
                lexer.nextToken();
                SQLExpr comment = this.primary();
                primaryKey.setComment(comment);
            } else if (lexer.identifierEquals(FnvHash.Constants.KEY_BLOCK_SIZE)) {
                lexer.nextToken();
                if (lexer.token() == Token.EQ) {
                    lexer.nextToken();
                }
                SQLExpr keyBlockSize = this.primary();
                primaryKey.setKeyBlockSize(keyBlockSize);
            } else if (lexer.identifierEquals(FnvHash.Constants.USING)) {
                lexer.nextToken();
                primaryKey.setIndexType(lexer.stringVal());
                accept(Token.IDENTIFIER);
            } else {
                break;
            }
        }

        return primaryKey;
        */
    }

    public MySqlUnique parseUnique() {
        MySqlUnique unique = new MySqlUnique();
        parseIndex(unique.getIndexDefinition());
        return unique;
    }

    protected SQLForeignKeyImpl createForeignKey() {
        return new MysqlForeignKey();
    }

    public MysqlForeignKey parseForeignKey() {
        accept(Token.FOREIGN);
        accept(Token.KEY);

        MysqlForeignKey fk = new MysqlForeignKey();

        if (lexer.token() != Token.LPAREN) {
            SQLName indexName = name();
            fk.setIndexName(indexName);
        }

        accept(Token.LPAREN);
        this.names(fk.getReferencingColumns(), fk);
        accept(Token.RPAREN);

        accept(Token.REFERENCES);

        fk.setReferencedTableName(this.name());

        accept(Token.LPAREN);
        this.names(fk.getReferencedColumns());
        accept(Token.RPAREN);

        if (lexer.identifierEquals(FnvHash.Constants.MATCH)) {
            lexer.nextToken();
            if (lexer.identifierEquals("FULL") || lexer.token() == Token.FULL) {
                fk.setReferenceMatch(Match.FULL);
                lexer.nextToken();
            } else if (lexer.identifierEquals(FnvHash.Constants.PARTIAL)) {
                fk.setReferenceMatch(Match.PARTIAL);
                lexer.nextToken();
            } else if (lexer.identifierEquals(FnvHash.Constants.SIMPLE)) {
                fk.setReferenceMatch(Match.SIMPLE);
                lexer.nextToken();
            } else {
                throw new ParserException("TODO : " + lexer.info());
            }
        }

        while (lexer.token() == Token.ON) {
            lexer.nextToken();

            if (lexer.token() == Token.DELETE) {
                lexer.nextToken();

                Option option = parseReferenceOption();
                fk.setOnDelete(option);
            } else if (lexer.token() == Token.UPDATE) {
                lexer.nextToken();

                Option option = parseReferenceOption();
                fk.setOnUpdate(option);
            } else {
                throw new ParserException("syntax error, expect DELETE or UPDATE, actual " + lexer.token() + " "
                        + lexer.info());
            }
        }
        return fk;
    }

    protected SQLAggregateExpr parseAggregateExprRest(SQLAggregateExpr aggregateExpr) {
        if (lexer.token() == Token.ORDER) {
            SQLOrderBy orderBy = this.parseOrderBy();
            aggregateExpr.setOrderBy(orderBy);
            //为了兼容之前的逻辑
            aggregateExpr.putAttribute("ORDER BY", orderBy);
        }
        if (lexer.identifierEquals(FnvHash.Constants.SEPARATOR)) {
            lexer.nextToken();

            SQLExpr seperator = this.primary();
            seperator.setParent(aggregateExpr);

            aggregateExpr.putAttribute("SEPARATOR", seperator);
        }
        return aggregateExpr;
    }

    public MySqlOrderingExpr parseSelectGroupByItem() {
        MySqlOrderingExpr item = new MySqlOrderingExpr();

        item.setExpr(expr());

        if (lexer.token() == Token.ASC) {
            lexer.nextToken();
            item.setType(SQLOrderingSpecification.ASC);
        } else if (lexer.token() == Token.DESC) {
            lexer.nextToken();
            item.setType(SQLOrderingSpecification.DESC);
        }

        return item;
    }

    public SQLSubPartition parseSubPartition() {
        SQLSubPartition subPartition = new SQLSubPartition();
        subPartition.setName(this.name());

        for (; ; ) {
            boolean storage = false;
            if (lexer.identifierEquals(FnvHash.Constants.DATA)) {
                lexer.nextToken();
                acceptIdentifier("DIRECTORY");
                if (lexer.token() == Token.EQ) {
                    lexer.nextToken();
                }
                subPartition.setDataDirectory(this.expr());
            } else if (lexer.token() == Token.TABLESPACE) {
                lexer.nextToken();
                if (lexer.token() == Token.EQ) {
                    lexer.nextToken();
                }
                SQLName tableSpace = this.name();
                subPartition.setTablespace(tableSpace);
            } else if (lexer.token() == Token.INDEX) {
                lexer.nextToken();
                acceptIdentifier("DIRECTORY");
                if (lexer.token() == Token.EQ) {
                    lexer.nextToken();
                }
                subPartition.setIndexDirectory(this.expr());
            } else if (lexer.identifierEquals(FnvHash.Constants.MAX_ROWS)) {
                lexer.nextToken();
                if (lexer.token() == Token.EQ) {
                    lexer.nextToken();
                }
                SQLExpr maxRows = this.primary();
                subPartition.setMaxRows(maxRows);
            } else if (lexer.identifierEquals(FnvHash.Constants.MIN_ROWS)) {
                lexer.nextToken();
                if (lexer.token() == Token.EQ) {
                    lexer.nextToken();
                }
                SQLExpr minRows = this.primary();
                subPartition.setMinRows(minRows);
            } else if (lexer.identifierEquals(FnvHash.Constants.ENGINE) || //
                    (storage = (lexer.token() == Token.STORAGE || lexer.identifierEquals(FnvHash.Constants.STORAGE)))) {
                if (storage) {
                    lexer.nextToken();
                }
                acceptIdentifier("ENGINE");

                if (lexer.token() == Token.EQ) {
                    lexer.nextToken();
                }

                SQLName engine = this.name();
                subPartition.setEngine(engine);
            } else if (lexer.token() == Token.COMMENT) {
                lexer.nextToken();
                if (lexer.token() == Token.EQ) {
                    lexer.nextToken();
                }
                SQLExpr comment = this.primary();
                subPartition.setComment(comment);
            } else {
                break;
            }
        }

        return subPartition;
    }

    public MysqlPartitionSingle parsePartition() {
        if (lexer.identifierEquals(FnvHash.Constants.DBPARTITION)
                || lexer.identifierEquals(FnvHash.Constants.TBPARTITION)
                || lexer.identifierEquals(FnvHash.Constants.SUBPARTITION)) {
            lexer.nextToken();
        } else {
            accept(Token.PARTITION);
        }

    MysqlPartitionSingle partitionDef = new MysqlPartitionSingle();

        SQLName name;
        if (lexer.token() == Token.LITERAL_INT) {
            Number number = lexer.integerValue();
            name = new SQLIdentifierExpr(number.toString());
            lexer.nextToken();
        } else {
            name = this.name();
        }
        partitionDef.setName(name);

        SQLPartitionValue values = this.parsePartitionValues();
        if (values != null) {
            partitionDef.setValues(values);
        }

        for (; ; ) {
            boolean storage = false;
            if (lexer.identifierEquals(FnvHash.Constants.DATA)) {
                lexer.nextToken();
                acceptIdentifier("DIRECTORY");
                if (lexer.token() == Token.EQ) {
                    lexer.nextToken();
                }
                partitionDef.setDataDirectory(this.expr());
            } else if (lexer.token() == Token.TABLESPACE) {
                lexer.nextToken();
                if (lexer.token() == Token.EQ) {
                    lexer.nextToken();
                }
                SQLName tableSpace = this.name();
                partitionDef.setTablespace(tableSpace);
            } else if (lexer.token() == Token.INDEX) {
                lexer.nextToken();
                acceptIdentifier("DIRECTORY");
                if (lexer.token() == Token.EQ) {
                    lexer.nextToken();
                }
                partitionDef.setIndexDirectory(this.expr());
            } else if (lexer.identifierEquals(FnvHash.Constants.MAX_ROWS)) {
                lexer.nextToken();
                if (lexer.token() == Token.EQ) {
                    lexer.nextToken();
                }
                SQLExpr maxRows = this.primary();
                partitionDef.setMaxRows(maxRows);
            } else if (lexer.identifierEquals(FnvHash.Constants.MIN_ROWS)) {
                lexer.nextToken();
                if (lexer.token() == Token.EQ) {
                    lexer.nextToken();
                }
                SQLExpr minRows = this.primary();
                partitionDef.setMaxRows(minRows);
            } else if (lexer.identifierEquals(FnvHash.Constants.ENGINE) || //
                    (storage = (lexer.token() == Token.STORAGE || lexer.identifierEquals(FnvHash.Constants.STORAGE)))) {
                if (storage) {
                    lexer.nextToken();
                }
                acceptIdentifier("ENGINE");

                if (lexer.token() == Token.EQ) {
                    lexer.nextToken();
                }

                SQLName engine = this.name();
                partitionDef.setEngine(engine);
            } else if (lexer.token() == Token.COMMENT) {
                lexer.nextToken();
                if (lexer.token() == Token.EQ) {
                    lexer.nextToken();
                }
                SQLExpr comment = this.primary();
                partitionDef.setComment(comment);
            } else {
                break;
            }
        }

        if (lexer.token() == Token.LPAREN) {
            lexer.nextToken();

            for (; ; ) {
                acceptIdentifier("SUBPARTITION");

                SQLSubPartition subPartition = parseSubPartition();

                partitionDef.addSubPartition(subPartition);

                if (lexer.token() == Token.COMMA) {
                    lexer.nextToken();
                    continue;
                }
                break;
            }

            accept(Token.RPAREN);
        }

        if (lexer.identifierEquals("LOCALITY")) {
            lexer.nextToken();
            accept(Token.EQ);
            SQLExpr locality = this.expr();
            partitionDef.setLocality(locality);
        }

        return partitionDef;
    }

    protected SQLExpr parseAliasExpr(String alias) {
        if (isEnabled(SQLParserFeature.KeepNameQuotes)) {
            return new SQLIdentifierExpr(alias);
        }
        Lexer newLexer = new Lexer(alias);
        newLexer.nextTokenValue();
        return new SQLCharExpr(newLexer.stringVal());
    }

    public boolean parseTableOptions(List<SQLAssignItem> assignItems, SQLDDLStatement parent) {
        // Check whether table options.
        boolean succeed = false;

        while (lexer.token() != Token.EOF) {
            final long hash = lexer.hashLCase();
            final int idx = Arrays.binarySearch(SINGLE_WORD_TABLE_OPTIONS_CODES, hash);
            SQLAssignItem assignItem = null;
            Lexer.SavePoint mark = null;

            if (idx >= 0 && idx < SINGLE_WORD_TABLE_OPTIONS_CODES.length &&
                    SINGLE_WORD_TABLE_OPTIONS_CODES[idx] == hash &&
                    (lexer.token() == Token.IDENTIFIER || (lexer.token().name != null && lexer.token().name.length() == SINGLE_WORD_TABLE_OPTIONS[idx].length()))) {
                // Special items.
                if (lexer.token() == Token.TABLESPACE) {
                    lexer.nextToken();

                    MySqlCreateTableStatement.TableSpaceOption option = new MySqlCreateTableStatement.TableSpaceOption();
                    option.setName(name());

                    if (lexer.identifierEquals("STORAGE")) {
                        lexer.nextToken();
                        option.setStorage(name());
                    }
                    assignItem = new SQLAssignItem(new SQLIdentifierExpr("TABLESPACE"), option);
                } else if (lexer.token() == Token.UNION) {
                    lexer.nextToken();
                    if (lexer.token() == Token.EQ) {
                        lexer.nextToken();
                    }

                    accept(Token.LPAREN);
                    SQLListExpr list = new SQLListExpr();
                    exprList(list.getItems(), list);
                    accept(Token.RPAREN);
                    assignItem = new SQLAssignItem(new SQLIdentifierExpr("UNION"), list);
                } else if (lexer.identifierEquals("PACK_KEYS")) {
                    // Caution: Not in MySql documents.
                    lexer.nextToken();
                    if (lexer.token() == Token.EQ) {
                        lexer.nextToken();
                    }

                    if (lexer.identifierEquals("PACK")) {
                        lexer.nextToken();
                        accept(Token.ALL);
                        assignItem = new SQLAssignItem(new SQLIdentifierExpr("PACK_KEYS"), new SQLIdentifierExpr("PACK ALL"));
                    } else {
                        assignItem = new SQLAssignItem(new SQLIdentifierExpr("PACK_KEYS"), expr());
                    }
                } else if (lexer.identifierEquals(FnvHash.Constants.ENGINE)) {
                    lexer.nextToken();
                    if (lexer.token() == Token.EQ) {
                        lexer.nextToken();
                    }

                    SQLExpr expr;
                    if (lexer.token() == Token.MERGE) {
                        expr = new SQLIdentifierExpr(lexer.stringVal());
                        lexer.nextToken();
                    } else {
                        expr = expr();
                    }
                    assignItem = new SQLAssignItem(new SQLIdentifierExpr("ENGINE"), expr);
                } else {
                    // Find single key, store as KV.
                    lexer.nextToken();
                    if (lexer.token() == Token.EQ) {
                        lexer.nextToken();
                    }

                    // STORAGE_POLICY
                    assignItem = new SQLAssignItem(
                            new SQLIdentifierExpr(SINGLE_WORD_TABLE_OPTIONS[idx]),
                            idx == 9 ? charExpr() : expr()
                    );
                }
            } else {
                // Following may not table options. Save mark.
                mark = lexer.mark();

                if (lexer.token() == Token.DEFAULT) {
                    // [DEFAULT] CHARACTER SET [=] charset_name
                    // [DEFAULT] COLLATE [=] collation_name
                    lexer.nextToken();
                    if (lexer.identifierEquals(FnvHash.Constants.CHARACTER)) {
                        lexer.nextToken();
                        accept(Token.SET);
                        if (lexer.token() == Token.EQ) {
                            lexer.nextToken();
                        }
                        if (lexer.token() == Token.IDENTIFIER) {
                            assignItem = new SQLAssignItem(new SQLIdentifierExpr("CHARACTER SET"), new SQLIdentifierExpr(lexer.stringVal()));
                            lexer.nextToken();
                        } else if (lexer.token() == Token.LITERAL_ALIAS || lexer.token() == Token.LITERAL_CHARS) {
                            String charset = lexer.stringVal();
                            if (charset.startsWith("\"")) {
                                charset = charset.substring(1, charset.length() - 1);
                            }
                            assignItem = new SQLAssignItem(new SQLIdentifierExpr("CHARACTER SET"), new SQLCharExpr(charset));
                            lexer.nextToken();
                        } else {
                            assignItem = new SQLAssignItem(new SQLIdentifierExpr("CHARACTER SET"), expr());
                        }
                    } else if (lexer.identifierEquals(FnvHash.Constants.COLLATE)) {
                        lexer.nextToken();
                        if (lexer.token() == Token.EQ) {
                            lexer.nextToken();
                        }
                        assignItem = new SQLAssignItem(new SQLIdentifierExpr("COLLATE"), expr());
                    }
                } else if (hash == FnvHash.Constants.CHARACTER) {
                    // CHARACTER SET [=] charset_name
                    lexer.nextToken();
                    accept(Token.SET);
                    if (lexer.token() == Token.EQ) {
                        lexer.nextToken();
                    } if (lexer.token() == Token.IDENTIFIER) {
                        assignItem = new SQLAssignItem(new SQLIdentifierExpr("CHARACTER SET"), new SQLIdentifierExpr(lexer.stringVal()));
                        lexer.nextToken();
                    } else if (lexer.token() == Token.LITERAL_ALIAS || lexer.token() == Token.LITERAL_CHARS) {
                        String charset = lexer.stringVal();
                        if (charset.startsWith("\"")) {
                            charset = charset.substring(1, charset.length() - 1);
                        }
                        assignItem = new SQLAssignItem(new SQLIdentifierExpr("CHARACTER SET"), new SQLCharExpr(charset));
                        lexer.nextToken();
                    } else {
                        assignItem = new SQLAssignItem(new SQLIdentifierExpr("CHARACTER SET"), expr());
                    }
                } else if (hash == FnvHash.Constants.DATA ||
                        lexer.token() == Token.INDEX) {
                    // {DATA|INDEX} DIRECTORY [=] 'absolute path to directory'
                    lexer.nextToken();
                    if (lexer.identifierEquals("DIRECTORY")) {
                        lexer.nextToken();
                        if (lexer.token() == Token.EQ) {
                            lexer.nextToken();
                        }
                        assignItem = new SQLAssignItem(new SQLIdentifierExpr("COLLATE"), expr());
                    }
                }
            }

            if (assignItem != null) {
                assignItem.setParent(parent);
                assignItems.add(assignItem);
                succeed = true;
            } else {
                if (mark != null) {
                    lexer.reset(mark);
                }
                return succeed;
            }

            // Optional comma.
            if (lexer.token() == Token.COMMA) {
                lexer.nextToken();
            } else if (lexer.token() == Token.EOF) {
                break;
            }
        }
        return succeed;
    }

    public SQLCheck parseCheck() {
        SQLCheck check = super.parseCheck();
        boolean enforce = true;
        if (lexer.token() == Token.NOT) {
            enforce = false;
            lexer.nextToken();
        }
        if (lexer.nextIfIdentifier("ENFORCED")) {
            check.setEnforced(enforce);
        }
        return check;
    }
}
