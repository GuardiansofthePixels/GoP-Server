package de.julianweinelt.gop.except;

public class ModuleInvalidException extends RuntimeException {
  public ModuleInvalidException(String message) {
    super(message);
  }
}
