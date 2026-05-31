package hellcat.core.middleware;

import hellcat.core.request.HellcatRequest;
import java.util.function.Function;

@FunctionalInterface
public interface HellcatMiddleware {
    Object Apply(HellcatRequest Request, Function<HellcatRequest, Object> Next);
}
