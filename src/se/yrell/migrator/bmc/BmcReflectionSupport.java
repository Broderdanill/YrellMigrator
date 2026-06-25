package se.yrell.migrator.bmc;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.Comparator;

/**
 * Centralized reflection guard for Developer Studio/BMC internal API access.
 *
 * Developer Studio exposes some object details only through version-specific public getters.
 * Keeping the safety rules here makes future reflection-heavy code easier to audit.
 */
public final class BmcReflectionSupport {
    private BmcReflectionSupport() {
    }

    public static Method[] safePublicMethods(Object object) {
        if (object == null) {
            return new Method[0];
        }
        try {
            Method[] methods = object.getClass().getMethods();
            Arrays.sort(methods, new Comparator<Method>() {
                public int compare(Method left, Method right) {
                    return left.getName().compareTo(right.getName());
                }
            });
            return methods;
        } catch (Throwable ex) {
            return new Method[0];
        }
    }

    public static boolean isSafeNoArgGetter(Method method) {
        if (method == null || method.getParameterTypes().length != 0 || void.class.equals(method.getReturnType())) {
            return false;
        }
        if (!Modifier.isPublic(method.getModifiers())) {
            return false;
        }
        String name = method.getName();
        if ("getClass".equals(name)) {
            return false;
        }
        return name.startsWith("get") || name.startsWith("is");
    }

    public static Object invokeNoArg(Object target, Method method) throws Throwable {
        try {
            return method.invoke(target, new Object[0]);
        } catch (InvocationTargetException ex) {
            Throwable cause = ex.getTargetException();
            throw cause == null ? ex : cause;
        }
    }

    public static String propertyName(Method method) {
        return propertyName(method == null ? null : method.getName());
    }

    public static String propertyName(String methodName) {
        if (methodName == null) {
            return "";
        }
        String name = methodName.startsWith("get") && methodName.length() > 3 ? methodName.substring(3)
                : methodName.startsWith("is") && methodName.length() > 2 ? methodName.substring(2) : methodName;
        if (name.length() == 0) {
            return "";
        }
        return Character.toLowerCase(name.charAt(0)) + name.substring(1);
    }

    public static String safeMessage(Throwable ex) {
        if (ex == null) {
            return "unknown";
        }
        String message = ex.getLocalizedMessage();
        return message == null || message.length() == 0 ? ex.getClass().getName() : message;
    }
}
