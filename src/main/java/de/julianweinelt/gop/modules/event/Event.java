package de.julianweinelt.gop.modules.event;

import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

import java.lang.annotation.*;
import java.lang.reflect.Method;
import java.util.*;


// Basisklasse für Events
@Setter
@Getter
public class Event {
    private boolean cancelled = false;

}