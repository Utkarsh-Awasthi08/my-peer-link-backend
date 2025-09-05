package p2p.adapter;

import com.sun.net.httpserver.HttpExchange;
import org.apache.commons.fileupload.RequestContext;

import java.io.IOException;
import java.io.InputStream;

public class HttpExchangeRequestContext implements RequestContext {
    private final HttpExchange exchange;

    public HttpExchangeRequestContext(HttpExchange exchange) {
        this.exchange = exchange;
    }

    @Override
    public String getCharacterEncoding() {
        return "UTF-8";
    }

    @Override
    public String getContentType() {
        return exchange.getRequestHeaders().getFirst("Content-type");
    }

    @Override
    public int getContentLength() {
        String length = exchange.getRequestHeaders().getFirst("Content-length");
        return length == null ? -1 : Integer.parseInt(length);
    }

    @Override
    public InputStream getInputStream() throws IOException {
        return exchange.getRequestBody();
    }
}