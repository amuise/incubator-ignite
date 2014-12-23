/* @java.file.header */

/*  _________        _____ __________________        _____
 *  __  ____/___________(_)______  /__  ____/______ ____(_)_______
 *  _  / __  __  ___/__  / _  __  / _  / __  _  __ `/__  / __  __ \
 *  / /_/ /  _  /    _  /  / /_/ /  / /_/ /  / /_/ / _  /  _  / / /
 *  \____/   /_/     /_/   \_,__/   \____/   \__,_/  /_/   /_/ /_/
 */

package org.gridgain.grid.kernal.processors.query.h2.sql;

import org.apache.ignite.*;
import org.h2.command.dml.*;
import org.h2.expression.*;
import org.h2.result.*;
import org.h2.table.*;
import org.jetbrains.annotations.*;

import java.lang.reflect.*;
import java.util.*;
import java.util.Set;

import static org.gridgain.grid.kernal.processors.query.h2.sql.GridSqlOperationType.*;

/**
 * H2 Query parser.
 */
@SuppressWarnings("TypeMayBeWeakened")
public class GridSqlQueryParser {
    /** */
    private static final GridSqlOperationType[] OPERATION_OP_TYPES = new GridSqlOperationType[]{CONCAT, PLUS, MINUS, MULTIPLY, DIVIDE, null, MODULUS};

    /** */
    private static final GridSqlOperationType[] COMPARISON_TYPES = new GridSqlOperationType[]{EQUAL, BIGGER_EQUAL, BIGGER, SMALLER_EQUAL,
        SMALLER, NOT_EQUAL, IS_NULL, IS_NOT_NULL,
        null, null, null, SPATIAL_INTERSECTS /* 11 */, null, null, null, null, EQUAL_NULL_SAFE /* 16 */, null, null, null, null,
        NOT_EQUAL_NULL_SAFE /* 21 */};

    /** */
    private static final Getter<Select, Expression> CONDITION = getter(Select.class, "condition");

    /** */
    private static final Getter<Select, int[]> GROUP_INDEXES = getter(Select.class, "groupIndex");

    /** */
    private static final Getter<Operation, Integer> OPERATION_TYPE = getter(Operation.class, "opType");

    /** */
    private static final Getter<Operation, Expression> OPERATION_LEFT = getter(Operation.class, "left");

    /** */
    private static final Getter<Operation, Expression> OPERATION_RIGHT = getter(Operation.class, "right");

    /** */
    private static final Getter<Comparison, Integer> COMPARISON_TYPE = getter(Comparison.class, "compareType");

    /** */
    private static final Getter<Comparison, Expression> COMPARISON_LEFT = getter(Comparison.class, "left");

    /** */
    private static final Getter<Comparison, Expression> COMPARISON_RIGHT = getter(Comparison.class, "right");

    /** */
    private static final Getter<ConditionAndOr, Integer> ANDOR_TYPE = getter(ConditionAndOr.class, "andOrType");

    /** */
    private static final Getter<ConditionAndOr, Expression> ANDOR_LEFT = getter(ConditionAndOr.class, "left");

    /** */
    private static final Getter<ConditionAndOr, Expression> ANDOR_RIGHT = getter(ConditionAndOr.class, "right");

    /** */
    private static final Getter<TableView, Query> VIEW_QUERY = getter(TableView.class, "viewQuery");

    /** */
    private static final Getter<TableFilter, String> ALIAS = getter(TableFilter.class, "alias");

    /** */
    private static final Getter<Select, Integer> HAVING_INDEX = getter(Select.class, "havingIndex");

    /** */
    private static final Getter<ConditionIn, Expression> LEFT_CI = getter(ConditionIn.class, "left");

    /** */
    private static final Getter<ConditionIn, List<Expression>> VALUE_LIST_CI = getter(ConditionIn.class, "valueList");

    /** */
    private static final Getter<ConditionInConstantSet, Expression> LEFT_CICS =
        getter(ConditionInConstantSet.class, "left");

    /** */
    private static final Getter<ConditionInConstantSet, List<Expression>> VALUE_LIST_CICS =
        getter(ConditionInConstantSet.class, "valueList");

    /** */
    private static final Getter<ConditionInSelect, Expression> LEFT_CIS = getter(ConditionInSelect.class, "left");

    /** */
    private static final Getter<ConditionInSelect, Boolean> ALL = getter(ConditionInSelect.class, "all");

    /** */
    private static final Getter<ConditionInSelect, Integer> COMPARE_TYPE = getter(ConditionInSelect.class,
        "compareType");

    /** */
    private static final Getter<ConditionInSelect, Query> QUERY = getter(ConditionInSelect.class, "query");

    /** */
    private static final Getter<CompareLike, Expression> LEFT = getter(CompareLike.class, "left");

    /** */
    private static final Getter<CompareLike, Expression> RIGHT = getter(CompareLike.class, "right");

    /** */
    private static final Getter<CompareLike, Expression> ESCAPE = getter(CompareLike.class, "escape");

    /** */
    private static final Getter<CompareLike, Boolean> REGEXP_CL = getter(CompareLike.class, "regexp");

    /** */
    private static final Getter<Aggregate, Boolean> DISTINCT = getter(Aggregate.class, "distinct");

    /** */
    private static final Getter<Aggregate, Integer> TYPE = getter(Aggregate.class, "type");

    /** */
    private static final Getter<Aggregate, Expression> ON = getter(Aggregate.class, "on");

    /** */
    private final IdentityHashMap<Object, Object> h2ObjToGridObj = new IdentityHashMap<>();

    /**
     * @param filter Filter.
     */
    private GridSqlElement toGridTableFilter(TableFilter filter) {
        GridSqlElement res = (GridSqlElement)h2ObjToGridObj.get(filter);

        if (res == null) {
            Table tbl = filter.getTable();

            if (tbl instanceof TableBase)
                res = new GridSqlTable(tbl.getSchema().getName(), tbl.getName());
            else if (tbl instanceof TableView) {
                Query qry = VIEW_QUERY.get((TableView)tbl);

                assert0(qry instanceof Select, qry);

                res = new GridSqlSubquery(toGridSelect((Select)qry));
            }
            else
                throw new IgniteException("Unsupported query: " + filter);

            String alias = ALIAS.get(filter);

            if (alias != null)
                res = new GridSqlAlias(alias, res, false);

            h2ObjToGridObj.put(filter, res);
        }

        return res;
    }

    /**
     * @param select Select.
     */
    public GridSqlSelect toGridSelect(Select select) {
        GridSqlSelect res = (GridSqlSelect)h2ObjToGridObj.get(select);

        if (res != null)
            return res;

        res = new GridSqlSelect();

        h2ObjToGridObj.put(select, res);

        res.distinct(select.isDistinct());

        Expression where = CONDITION.get(select);
        res.where(toGridExpression(where));

        Set<TableFilter> allFilers = new HashSet<>(select.getTopFilters());

        GridSqlElement from = null;

        TableFilter filter = select.getTopTableFilter();
        do {
            assert0(filter != null, select);
            assert0(!filter.isJoinOuter(), select);
            assert0(filter.getNestedJoin() == null, select);
            assert0(filter.getJoinCondition() == null, select);
            assert0(filter.getFilterCondition() == null, select);

            GridSqlElement gridFilter = toGridTableFilter(filter);

            from = from == null ? gridFilter : new GridSqlJoin(from, gridFilter);

            allFilers.remove(filter);

            filter = filter.getJoin();
        }
        while (filter != null);

        res.from(from);

        assert allFilers.isEmpty();

        ArrayList<Expression> expressions = select.getExpressions();

        int[] grpIdx = GROUP_INDEXES.get(select);

        if (grpIdx != null) {
            for (int idx : grpIdx)
                res.addGroupExpression(toGridExpression(expressions.get(idx)));
        }

        assert0(select.getHaving() == null, select);

        int havingIdx = HAVING_INDEX.get(select);

        if (havingIdx >= 0)
            res.having(toGridExpression(expressions.get(havingIdx)));

        for (int i = 0; i < select.getColumnCount(); i++)
            res.addSelectExpression(toGridExpression(expressions.get(i)));

        SortOrder sortOrder = select.getSortOrder();

        if (sortOrder != null) {
            int[] indexes = sortOrder.getQueryColumnIndexes();
            int[] sortTypes = sortOrder.getSortTypes();

            for (int i = 0; i < indexes.length; i++)
                res.addSort(toGridExpression(expressions.get(indexes[i])), sortTypes[i]);
        }

        return res;
    }

    /**
     * @param expression Expression.
     */
    private GridSqlElement toGridExpression(@Nullable Expression expression) {
        if (expression == null)
            return null;

        GridSqlElement res = (GridSqlElement)h2ObjToGridObj.get(expression);

        if (res == null) {
            res = toGridExpression0(expression);

            h2ObjToGridObj.put(expression, res);
        }

        return res;
    }

    /**
     * @param expression Expression.
     */
    @NotNull private GridSqlElement toGridExpression0(@NotNull Expression expression) {
        if (expression instanceof ExpressionColumn) {
            TableFilter tblFilter = ((ExpressionColumn)expression).getTableFilter();

            GridSqlElement gridTblFilter = toGridTableFilter(tblFilter);

            return new GridSqlColumn(gridTblFilter, expression.getColumnName(), expression.getSQL());
        }

        if (expression instanceof Alias)
            return new GridSqlAlias(expression.getAlias(), toGridExpression(expression.getNonAliasExpression()), true);

        if (expression instanceof ValueExpression)
            return new GridSqlConst(expression.getValue(null));

        if (expression instanceof Operation) {
            Operation operation = (Operation)expression;

            Integer type = OPERATION_TYPE.get(operation);

            if (type == Operation.NEGATE) {
                assert OPERATION_RIGHT.get(operation) == null;

                return new GridSqlOperation(GridSqlOperationType.NEGATE, toGridExpression(OPERATION_LEFT.get(operation)));
            }

            return new GridSqlOperation(OPERATION_OP_TYPES[type],
                toGridExpression(OPERATION_LEFT.get(operation)),
                toGridExpression(OPERATION_RIGHT.get(operation)));
        }

        if (expression instanceof Comparison) {
            Comparison cmp = (Comparison)expression;

            GridSqlOperationType opType = COMPARISON_TYPES[COMPARISON_TYPE.get(cmp)];

            assert opType != null : COMPARISON_TYPE.get(cmp);

            GridSqlElement left = toGridExpression(COMPARISON_LEFT.get(cmp));

            if (opType.childrenCount() == 1)
                return new GridSqlOperation(opType, left);

            GridSqlElement right = toGridExpression(COMPARISON_RIGHT.get(cmp));

            return new GridSqlOperation(opType, left, right);
        }

        if (expression instanceof ConditionNot)
            return new GridSqlOperation(NOT, toGridExpression(expression.getNotIfPossible(null)));

        if (expression instanceof ConditionAndOr) {
            ConditionAndOr andOr = (ConditionAndOr)expression;

            int type = ANDOR_TYPE.get(andOr);

            assert type == ConditionAndOr.AND || type == ConditionAndOr.OR;

            return new GridSqlOperation(type == ConditionAndOr.AND ? AND : OR,
                toGridExpression(ANDOR_LEFT.get(andOr)), toGridExpression(ANDOR_RIGHT.get(andOr)));
        }

        if (expression instanceof Subquery) {
            Query qry = ((Subquery)expression).getQuery();

            assert0(qry instanceof Select, expression);

            return new GridSqlSubquery(toGridSelect((Select)qry));
        }

        if (expression instanceof ConditionIn) {
            GridSqlOperation res = new GridSqlOperation(IN);

            res.addChild(toGridExpression(LEFT_CI.get((ConditionIn)expression)));

            List<Expression> vals = VALUE_LIST_CI.get((ConditionIn)expression);

            for (Expression val : vals)
                res.addChild(toGridExpression(val));

            return res;
        }

        if (expression instanceof ConditionInConstantSet) {
            GridSqlOperation res = new GridSqlOperation(IN);

            res.addChild(toGridExpression(LEFT_CICS.get((ConditionInConstantSet)expression)));

            List<Expression> vals = VALUE_LIST_CICS.get((ConditionInConstantSet)expression);

            for (Expression val : vals)
                res.addChild(toGridExpression(val));

            return res;
        }

        if (expression instanceof ConditionInSelect) {
            GridSqlOperation res = new GridSqlOperation(IN);

            boolean all = ALL.get((ConditionInSelect)expression);
            int compareType = COMPARE_TYPE.get((ConditionInSelect)expression);

            assert0(!all, expression);
            assert0(compareType == Comparison.EQUAL, expression);

            res.addChild(toGridExpression(LEFT_CIS.get((ConditionInSelect)expression)));

            Query qry = QUERY.get((ConditionInSelect)expression);

            assert0(qry instanceof Select, qry);

            res.addChild(new GridSqlSubquery(toGridSelect((Select)qry)));

            return res;
        }

        if (expression instanceof CompareLike) {
            assert0(ESCAPE.get((CompareLike)expression) == null, expression);

            boolean regexp = REGEXP_CL.get((CompareLike)expression);

            return new GridSqlOperation(regexp ? REGEXP : LIKE, toGridExpression(LEFT.get((CompareLike)expression)),
                toGridExpression(RIGHT.get((CompareLike)expression)));
        }

        if (expression instanceof Function) {
            Function f = (Function)expression;

            GridSqlFunction res = new GridSqlFunction(f.getName());

            for (Expression arg : f.getArgs())
                res.addChild(toGridExpression(arg));

            if (f.getFunctionType() == Function.CAST)
                res.setCastType(new Column(null, f.getType(), f.getPrecision(), f.getScale(), f.getDisplaySize())
                    .getCreateSQL());

            return res;
        }

        if (expression instanceof Parameter)
            return new GridSqlParameter(((Parameter)expression).getIndex());

        if (expression instanceof Aggregate) {
            GridSqlAggregateFunction res = new GridSqlAggregateFunction(DISTINCT.get((Aggregate)expression),
                TYPE.get((Aggregate)expression));

            Expression on = ON.get((Aggregate)expression);

            if (on != null)
                res.addChild(toGridExpression(on));

            return res;
        }

        throw new IgniteException("Unsupported expression: " + expression + " [type=" +
            expression.getClass().getSimpleName() + ']');
    }

    /**
     * @param cond Condition.
     * @param o Object.
     */
    private static void assert0(boolean cond, Object o) {
        if (!cond)
            throw new IgniteException("Unsupported query: " + o);
    }

    /**
     * @param cls Class.
     * @param fldName Fld name.
     */
    private static <T, R> Getter<T, R> getter(Class<T> cls, String fldName) {
        Field field;

        try {
            field = cls.getDeclaredField(fldName);
        }
        catch (NoSuchFieldException e) {
            throw new RuntimeException(e);
        }

        field.setAccessible(true);

        return new Getter<>(field);
    }

    /**
     * Field getter.
     */
    @SuppressWarnings("unchecked")
    private static class Getter<T, R> {
        /** */
        private final Field fld;

        /**
         * @param fld Fld.
         */
        private Getter(Field fld) {
            this.fld = fld;
        }

        /**
         * @param obj Object.
         * @return Result.
         */
        public R get(T obj) {
            try {
                return (R)fld.get(obj);
            }
            catch (IllegalAccessException e) {
                throw new IgniteException(e);
            }
        }
    }
}