package com.eric.governanceApi.governanceApi.audit;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import com.eric.governanceApi.governanceApi.enums.AuditAction;

@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Auditable {
    AuditAction action();
    String targetType() default "";
    /** 0-based index of the method argument to use as targetId. -1 = none. */
    int targetIdArg() default -1;
}
