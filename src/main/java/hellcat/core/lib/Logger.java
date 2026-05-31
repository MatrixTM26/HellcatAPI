package hellcat.core.lib;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

public class Logger {

    private static final String RESET = "\u001B[0m";
    private static final String RED = "\u001B[31m";
    private static final String GREEN = "\u001B[32m";
    private static final String WHITE = "\u001B[97m";
    private static final String BLUE = "\u001B[34m";
    private static final String YELLOW = "\u001B[33m";
    private static final String BOLD = "\u001B[1m";
    private static final String DIM = "\u001B[2m";

    private static final DateTimeFormatter TimeFormat = DateTimeFormatter.ofPattern("HH:mm:ss");

    private static String TimeStamp() {
        return LocalTime.now().format(TimeFormat);
    }

    private static String FormatMsg(String Format, Object[] Args) {
        return (Args == null || Args.length == 0) ? Format : String.format(Format, (Object[]) Args);
    }

    public static void INFO(String Format, Object... Args) {
        String Message = FormatMsg(Format, Args);
        System.out.println(
            BOLD +
            DIM +
            WHITE +
            "[" +
            TimeStamp() +
            "]" +
            RESET +
            BOLD +
            WHITE +
            "[" +
            BLUE +
            "INFO" +
            WHITE +
            "]" +
            DIM +
            " " +
            Message +
            RESET
        );
    }

    public static void DEBUG(String Format, Object... Args) {
        String Message = FormatMsg(Format, Args);
        System.out.println(
            BOLD +
            DIM +
            WHITE +
            "[" +
            TimeStamp() +
            "]" +
            RESET +
            BOLD +
            WHITE +
            "[" +
            GREEN +
            "DEBUG" +
            WHITE +
            "]" +
            DIM +
            " " +
            Message +
            RESET
        );
    }

    public static void WARNING(String Format, Object... Args) {
        String Message = FormatMsg(Format, Args);
        System.out.println(
            BOLD +
            DIM +
            WHITE +
            "[" +
            TimeStamp() +
            "]" +
            RESET +
            BOLD +
            WHITE +
            "[" +
            YELLOW +
            "WARNING" +
            WHITE +
            "]" +
            DIM +
            " " +
            Message +
            RESET
        );
    }

    public static void ERROR(String Format, Object... Args) {
        String Message = FormatMsg(Format, Args);
        System.out.println(
            BOLD +
            DIM +
            WHITE +
            "[" +
            TimeStamp() +
            "]" +
            RESET +
            BOLD +
            WHITE +
            "[" +
            RED +
            "ERROR" +
            WHITE +
            "]" +
            DIM +
            " " +
            Message +
            RESET
        );
    }
}
