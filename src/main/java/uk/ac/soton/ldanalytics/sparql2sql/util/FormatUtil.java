package uk.ac.soton.ldanalytics.sparql2sql.util;

import java.util.HashMap;
import java.util.Map;

import com.hp.hpl.jena.sparql.expr.Expr;
import com.hp.hpl.jena.sparql.expr.ExprFunction;
import com.hp.hpl.jena.sparql.expr.NodeValue;

public class FormatUtil {	
	public static String parseNodeValue(NodeValue node) {
		if(node.isDateTime()) {
			return node.getDateTime().toString();
		}
		return null;		
	}

	public static String processExprType(Expr expr, Map<String, String> varMapping) {
		String expression = "";
		if(expr.isFunction()) {
			ExprFunction func = expr.getFunction();
			if(func.getFunctionIRI()!=null)
				expression = mapVar(func.getArg(1).getVarName(),varMapping);
			else {
				String innerExpression = "";
				if(func.getArg(1).isFunction()) {
					innerExpression = processExprType(func.getArg(1),varMapping);
				} else {
					innerExpression = mapVar(func.getArg(1).getVarName(),varMapping);
				}
				expression = symbolMap(func.getFunctionSymbol().getSymbol()) + "(" + innerExpression + ")";
			}
		} else if(expr.isVariable()) {
			expression = mapVar(expr.getVarName(),varMapping);
		}
		return expression;
	}

	public static String symbolMap(String symbol) {
		String inputSymbol = symbol.toLowerCase();
		Map<String, String> symbolMap = new HashMap<String,String>();
		symbolMap.put("hours", "HOUR");
		if(symbolMap.containsKey(inputSymbol)) {
			inputSymbol = symbolMap.get(inputSymbol);
		}
		return inputSymbol;
	}

	public static String mapVar(String varName, Map<String, String> varMapping) {
		String mappedName = varName;
		if(varMapping.containsKey(varName))
			mappedName = varMapping.get(varName);
		return mappedName;
	}
	
	
}