package com.lhsg.HuiAnswer.rag.knowledge.dao.handler;

import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;
import org.postgresql.util.PGobject;

import java.sql.*;
import java.util.StringJoiner;

/** Java float[] 与 PostgreSQL vector 之间的转换，不负责调用 Embedding 模型。 */
public class PgVectorTypeHandler extends BaseTypeHandler<float[]> {
    @Override
    public void setNonNullParameter(PreparedStatement ps, int index, float[] value, JdbcType jdbcType)
            throws SQLException {
        if (value.length == 0) {
            throw new SQLException("向量不能为空");
        }
        StringJoiner text = new StringJoiner(",", "[", "]");
        for (float element : value) {
            if (!Float.isFinite(element)) {
                throw new SQLException("向量元素必须是有限数值");
            }
            text.add(Float.toString(element));
        }
        PGobject vector = new PGobject();
        vector.setType("vector");
        vector.setValue(text.toString());
        ps.setObject(index, vector);
    }

    @Override
    public float[] getNullableResult(ResultSet rs, String columnName) throws SQLException {
        return parse(rs.getString(columnName));
    }

    @Override
    public float[] getNullableResult(ResultSet rs, int columnIndex) throws SQLException {
        return parse(rs.getString(columnIndex));
    }

    @Override
    public float[] getNullableResult(CallableStatement cs, int columnIndex) throws SQLException {
        return parse(cs.getString(columnIndex));
    }

    private float[] parse(String value) throws SQLException {
        if (value == null) {
            return null;
        }
        try {
            String[] elements = value.substring(1, value.length() - 1).split(",");
            float[] result = new float[elements.length];
            for (int i = 0; i < elements.length; i++) {
                result[i] = Float.parseFloat(elements[i].trim());
            }
            return result;
        } catch (RuntimeException e) {
            throw new SQLException("读取 PostgreSQL 向量失败", e);
        }
    }
}
