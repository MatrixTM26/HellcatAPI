package hellcat.core.request;

import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.*;

public class HellcatRequestParser {

    public static HellcatRequest Parse(byte[] RawData, String[] RemoteAddress) {
        HellcatRequest Request = new HellcatRequest();
        Request.RemoteAddress = RemoteAddress;

        if (RawData == null || RawData.length == 0) throw new HellcatRequest.HellcatRequestParseException(
            "Empty request data received"
        );

        try {
            int SepIndex = IndexOfDoubleCrlf(RawData);
            byte[] HeaderBytes;
            byte[] BodyBytes;

            if (SepIndex == -1) {
                HeaderBytes = RawData;
                BodyBytes = new byte[0];
            } else {
                HeaderBytes = Arrays.copyOfRange(RawData, 0, SepIndex);
                BodyBytes = Arrays.copyOfRange(RawData, SepIndex + 4, RawData.length);
            }

            Request.Body = BodyBytes;
            String HeaderSection = new String(HeaderBytes, StandardCharsets.UTF_8);
            String[] Lines = HeaderSection.split("\r\n");

            if (Lines.length == 0 || Lines[0].isBlank()) throw new HellcatRequest.HellcatRequestParseException(
                "Missing HTTP request line"
            );

            ParseRequestLine(Request, Lines[0]);
            ParseHeaders(Request, Lines, 1);
            ParseCookies(Request);
            ParseBody(Request);
        } catch (HellcatRequest.HellcatRequestParseException E) {
            throw E;
        } catch (Exception E) {
            throw new HellcatRequest.HellcatRequestParseException(
                "Unexpected error while parsing request: " + E.getMessage()
            );
        }

        return Request;
    }

    private static int IndexOfDoubleCrlf(byte[] Data) {
        for (int I = 0; I < Data.length - 3; I++) {
            if (Data[I] == '\r' && Data[I + 1] == '\n' && Data[I + 2] == '\r' && Data[I + 3] == '\n') return I;
        }
        return -1;
    }

    private static void ParseRequestLine(HellcatRequest Request, String Line) {
        String[] Parts = Line.trim().split(" ", 3);
        if (Parts.length < 2) throw new HellcatRequest.HellcatRequestParseException(
            "Malformed HTTP request line: '" + Line + "'"
        );

        Request.Method = Parts[0].toUpperCase();
        String FullPath = Parts[1];
        Request.HttpVersion = Parts.length > 2 ? Parts[2] : "HTTP/1.1";

        int QPos = FullPath.indexOf('?');
        if (QPos >= 0) {
            Request.Path = Decode(FullPath.substring(0, QPos));
            Request.QueryParams = ParseQueryString(FullPath.substring(QPos + 1));
        } else {
            Request.Path = Decode(FullPath);
            Request.QueryParams = new HashMap<>();
        }
    }

    private static void ParseHeaders(HellcatRequest Request, String[] Lines, int Start) {
        for (int I = Start; I < Lines.length; I++) {
            String Line = Lines[I];
            int Colon = Line.indexOf(": ");
            if (Colon >= 0) {
                String Key = Line.substring(0, Colon).toLowerCase().trim();
                String Value = Line.substring(Colon + 2).trim();
                Request.Headers.put(Key, Value);
            }
        }
    }

    private static void ParseCookies(HellcatRequest Request) {
        String CookieHeader = Request.Headers.getOrDefault("cookie", "");
        if (CookieHeader.isEmpty()) return;

        for (String Pair : CookieHeader.split(";")) {
            Pair = Pair.trim();
            int Eq = Pair.indexOf('=');
            if (Eq >= 0) {
                Request.Cookies.put(Pair.substring(0, Eq).trim(), Pair.substring(Eq + 1).trim());
            }
        }
    }

    private static void ParseBody(HellcatRequest Request) {
        if (Request.IsJson()) {
            Request.GetJson();
        } else if (Request.IsForm()) {
            ParseFormUrlEncoded(Request);
        } else if (Request.IsMultipart()) {
            ParseMultipart(Request);
        }
    }

    private static void ParseFormUrlEncoded(HellcatRequest Request) {
        try {
            String BodyStr = new String(Request.Body, StandardCharsets.UTF_8);
            Request.Form = ParseQueryString(BodyStr);
        } catch (Exception E) {
            Request.Form = new HashMap<>();
        }
    }

    private static void ParseMultipart(HellcatRequest Request) {
        String ContentType = Request.GetContentType();
        if (!ContentType.contains("boundary=")) return;

        String BoundaryStr = ContentType.substring(ContentType.indexOf("boundary=") + 9).trim();
        byte[] Boundary = ("--" + BoundaryStr).getBytes(StandardCharsets.UTF_8);
        List<byte[]> Parts = SplitBytes(Request.Body, Boundary);

        for (int I = 1; I < Parts.size(); I++) {
            byte[] Part = Parts.get(I);
            String Trimmed = new String(Part, StandardCharsets.UTF_8).trim();
            if (Trimmed.equals("--") || Trimmed.isEmpty()) continue;

            int PartSep = IndexOfDoubleCrlf(Part);
            if (PartSep == -1) continue;

            byte[] PartBodyBytes = Arrays.copyOfRange(Part, PartSep + 4, Part.length);
            if (EndsWithCrlf(PartBodyBytes)) {
                PartBodyBytes = Arrays.copyOfRange(PartBodyBytes, 0, PartBodyBytes.length - 2);
            }

            String PartHeaders = new String(Arrays.copyOfRange(Part, 0, PartSep), StandardCharsets.UTF_8);
            String Disposition = "";
            String PartContentType = "application/octet-stream";

            for (String HLine : PartHeaders.split("\r\n")) {
                String Lower = HLine.toLowerCase();
                if (Lower.startsWith("content-disposition:")) {
                    Disposition = HLine;
                } else if (Lower.startsWith("content-type:")) {
                    PartContentType = HLine.substring(HLine.indexOf(':') + 1).trim();
                }
            }

            String FieldName = ExtractDispositionParam(Disposition, "name");
            String Filename = ExtractDispositionParam(Disposition, "filename");

            if (FieldName == null) continue;

            if (Filename != null) {
                Request.Files.put(FieldName, new HellcatUploadedFile(Filename, PartContentType, PartBodyBytes));
            } else {
                Request.Form.put(FieldName, new String(PartBodyBytes, StandardCharsets.UTF_8));
            }
        }
    }

    private static boolean EndsWithCrlf(byte[] Data) {
        return Data.length >= 2 && Data[Data.length - 2] == '\r' && Data[Data.length - 1] == '\n';
    }

    private static List<byte[]> SplitBytes(byte[] Source, byte[] Delimiter) {
        List<byte[]> Result = new ArrayList<>();
        int Start = 0;
        for (int I = 0; I <= Source.length - Delimiter.length; I++) {
            boolean Match = true;
            for (int J = 0; J < Delimiter.length; J++) {
                if (Source[I + J] != Delimiter[J]) {
                    Match = false;
                    break;
                }
            }
            if (Match) {
                Result.add(Arrays.copyOfRange(Source, Start, I));
                Start = I + Delimiter.length;
                I += Delimiter.length - 1;
            }
        }
        Result.add(Arrays.copyOfRange(Source, Start, Source.length));
        return Result;
    }

    private static String ExtractDispositionParam(String Disposition, String ParamName) {
        String Key = ParamName + "=\"";
        int Pos = Disposition.indexOf(Key);
        if (Pos == -1) return null;
        int Start = Pos + Key.length();
        int End = Disposition.indexOf('"', Start);
        if (End == -1) return null;
        return Disposition.substring(Start, End);
    }

    private static Map<String, String> ParseQueryString(String Query) {
        Map<String, String> Result = new LinkedHashMap<>();
        for (String Pair : Query.split("&")) {
            int Eq = Pair.indexOf('=');
            if (Eq >= 0) {
                Result.put(Decode(Pair.substring(0, Eq)), Decode(Pair.substring(Eq + 1)));
            } else if (!Pair.isEmpty()) {
                Result.put(Decode(Pair), "");
            }
        }
        return Result;
    }

    private static String Decode(String Value) {
        try {
            return URLDecoder.decode(Value, StandardCharsets.UTF_8.name());
        } catch (UnsupportedEncodingException E) {
            return Value;
        }
    }
}
