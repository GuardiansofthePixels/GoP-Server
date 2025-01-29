package de.julianweinelt.gop.modules.event;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

// Annotation für EventHandler
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
@interface EventHandler {}