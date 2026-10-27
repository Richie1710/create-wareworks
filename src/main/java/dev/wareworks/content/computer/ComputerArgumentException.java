package dev.wareworks.content.computer;

/**
 * A computer called a warehouse terminal's peripheral wrongly (issue #9, ADR-074): a bad argument, a terminal that is
 * gone, or computer access switched off on the server. The message is the English text the computer is shown as a Lua
 * error; the peripheral turns it into CC: Tweaked's {@code LuaException}, which this package cannot name.
 * <p>
 * A refusal of a well-formed request (no stock, a full queue) is <b>not</b> one of these: it is an ordinary answer.
 */
public final class ComputerArgumentException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public ComputerArgumentException(String message) {
        super(message);
    }
}
