package com.fire.sdk.http;

import java.io.ByteArrayInputStream;
import java.io.Closeable;
import java.io.IOException;

import com.google.gson.GsonBuilder;
import org.apache.http.*;
import org.apache.http.client.HttpClient;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpPut;
import org.apache.http.conn.HttpClientConnectionManager;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.NoConnectionReuseStrategy;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.impl.conn.BasicHttpClientConnectionManager;
import org.apache.http.protocol.HttpContext;
import org.apache.http.util.EntityUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fire.sdk.FireException;
import com.fire.sdk.model.Request;
import com.fire.sdk.model.Response;
import com.google.gson.Gson;

/**
 * HTTP Utils class for dealing with HTTP and actual message sending.
 * 
 * @author owenobyrne
 */
public class HttpUtils {

    private final static Logger logger = LoggerFactory.getLogger(HttpUtils.class);

    /**
     * Get a default HttpClient based on the HttpConfiguration object. If required the defaults can 
     * be altered to meet the requirements of the SDK user. The default client does not use connection 
     * pooling and does not reuse connections. Timeouts for connection and socket are taken from the 
     * {@link HttpConfiguration} object.
     * 
     * @param httpConfiguration An HttpConfiguration for this API client
     * @return a configured HttpClient
     */
    public static CloseableHttpClient getDefaultClient(HttpConfiguration httpConfiguration) {

        RequestConfig.Builder requestConfigBuilder = RequestConfig.custom().setConnectTimeout(httpConfiguration.getTimeout()).setRedirectsEnabled(false).setRelativeRedirectsAllowed(false)
                .setSocketTimeout(httpConfiguration.getTimeout());

        if (httpConfiguration.getProxy() != null) {
            requestConfigBuilder.setProxy(new HttpHost(httpConfiguration.getProxy().getHost(), httpConfiguration.getProxy().getPort()));
        }

        HttpClientConnectionManager connectionManager = new BasicHttpClientConnectionManager();
        ConnectionReuseStrategy connectionResuseStrategy = new NoConnectionReuseStrategy();

        logger.debug("Creating HttpClient with simple no pooling/no connection reuse default settings.");
        CloseableHttpClient httpClient = HttpClients.custom()
                        .addInterceptorLast(createHttpRequestInterceptor())
                        .addInterceptorLast(createHttpResponseInterceptor())
                        .setDefaultRequestConfig(requestConfigBuilder.build())
                        .setConnectionManager(connectionManager)
                .setConnectionReuseStrategy(connectionResuseStrategy).build();
        return httpClient;
    }

    private static HttpRequestInterceptor createHttpRequestInterceptor() {
        return new HttpRequestInterceptor() {
            
            @Override
            public void process(HttpRequest request, HttpContext context) throws HttpException, IOException {
                logger.info(">>>> REQUEST HEADERS >>>>");
                logger.info(">  {}", request.getRequestLine());
                for (Header header : request.getAllHeaders()) {
                   logger.info(">  {}: {}", header.getName(), header.getValue()); 
                }
                logger.info("");
                
                
                if(request instanceof HttpEntityEnclosingRequest) {
                    org.apache.http.HttpEntity rqEntity = ((HttpEntityEnclosingRequest) request).getEntity();
                    byte[] b = new byte[8192];
                    ((ByteArrayInputStream)rqEntity.getContent()).read(b, 0, (int)rqEntity.getContentLength());
                    logger.info(">>>> REQUEST CONTENT >>>>");
                    logger.info(">  {}", new String(b).trim());
                    logger.info("");
                }
                
            }
        };
    }
    
    
    private static HttpResponseInterceptor createHttpResponseInterceptor() {
        return new HttpResponseInterceptor() {
            
            @Override
            public void process(org.apache.http.HttpResponse response, HttpContext context) throws HttpException, IOException {
                
                logger.info("<<<< RESPONSE HEADERS <<<<");
                logger.info("<  {}", response.getStatusLine());
                for (Header header : response.getAllHeaders()) {
                    logger.info("<  {}: {}", header.getName(), header.getValue());
                }
                logger.info("");
            
                org.apache.http.HttpEntity entity = ((CloseableHttpResponse) response).getEntity();
                String body = (entity != null ? EntityUtils.toString(entity).trim() : "");
                logger.info("<<<< RESPONSE CONTENT <<<<");
                logger.info("<  {}", body);
                logger.info("");

                org.apache.http.HttpEntity newEntity = new StringEntity(body,ContentType.get(entity));
                response.setEntity(newEntity);
                        

                
            }
        };
    }

    
    /**
     * Internal Use. Perform the actual send of the message.
     *  
     * This will also check if only HTTPS is allowed, based on the {@link HttpConfiguration}, and will 
     * throw a {@link FireException} if HTTP is used when only HTTPS is allowed. A {@link FireException} 
     * is also thrown if the response from Fire is not success (ie. if it's not 200 status code). 
     * 
     * 
     * @param request a prepared request to send
     * @param <T> the class of the request object
     * @param <U> the class of the response object
     * @param accessToken the accessToken to use. 
     * @param httpClient the HttpClient to use.
     * @param httpConfiguration the HttpConfiguration to use. 
     * @return the response to the call. 
     */
    public static <T, U extends Response<U>> U sendPostMessage(Request<T, U> request, String accessToken, HttpClient httpClient, HttpConfiguration httpConfiguration) {
        Gson gson = new GsonBuilder().setDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").create();
        HttpPost httpPost = new HttpPost(httpConfiguration.getEndpoint() + "/" + request.getEndpoint());
        httpPost.addHeader(HttpHeaders.CONTENT_TYPE, ContentType.APPLICATION_JSON.getMimeType());
        
        if (accessToken != null) {
            httpPost.addHeader(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken);
        }
        
        HttpResponse httpResponse = null;

        try {
            httpPost.setEntity(new StringEntity(gson.toJson(request.getBody())));

            httpResponse = httpClient.execute(httpPost);
            
            String jsonResponse = null;
            if (httpResponse.getEntity() != null) {
                jsonResponse = EntityUtils.toString(httpResponse.getEntity());
            }
            
            int statusCode = (httpResponse.getStatusLine().getStatusCode());
            if (statusCode != HttpStatus.SC_OK && statusCode != HttpStatus.SC_ACCEPTED && statusCode != HttpStatus.SC_CREATED) {
                throw new FireException("Unexpected http status code [" + statusCode + "]");
            }

            
            
            EntityUtils.consume(httpResponse.getEntity());
            return (U) gson.fromJson(jsonResponse, request.getResponseClass());
            
        } catch (IOException ioe) {
            // Also catches ClientProtocolException (from httpClient.execute()) and UnsupportedEncodingException (from response.getEntity()
            logger.error("Exception communicating with Fire.", ioe.getMessage());
            throw new FireException("Exception communicating with Fire.", ioe);
        } finally {
            // Test if response Closeable
            if (httpResponse instanceof Closeable) {
                try {
                    ((Closeable) httpResponse).close();
                } catch (IOException ioe) {
                    // Ignore
                }
            }
        }
    }
    
    /**
     * Internal Use. Perform the actual send of the message.
     *  
     * This will also check if only HTTPS is allowed, based on the {@link HttpConfiguration}, and will 
     * throw a {@link FireException} if HTTP is used when only HTTPS is allowed. A {@link FireException} 
     * is also thrown if the response from Fire is not success (ie. if it's not 200 status code). 
     * 
     * 
     * @param request a prepared request to send
     * @param <T> the class of the request object
     * @param <U> the class of the response object
     * @param accessToken the accessToken to use. 
     * @param httpClient the HttpClient to use.
     * @param httpConfiguration the HttpConfiguration to use. 
     * @return the response to the call. 
     */
    public static <T, U extends Response<U>> U sendPutMessage(Request<T, U> request, String accessToken, HttpClient httpClient, HttpConfiguration httpConfiguration) {
        Gson gson = new Gson();
        HttpPut httpPut = new HttpPut(httpConfiguration.getEndpoint() + "/" + request.getEndpoint());
        httpPut.addHeader(HttpHeaders.CONTENT_TYPE, ContentType.APPLICATION_JSON.getMimeType());
        
        if (accessToken != null) {
            httpPut.addHeader(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken);
        }
        
        HttpResponse httpResponse = null;

        try {
            httpPut.setEntity(new StringEntity(gson.toJson(request.getBody())));

            httpResponse = httpClient.execute(httpPut);
            
            String jsonResponse = null;
            if (httpResponse.getEntity() != null) {
                jsonResponse = EntityUtils.toString(httpResponse.getEntity());
            }
            
            int statusCode = (httpResponse.getStatusLine().getStatusCode());
            if (statusCode != HttpStatus.SC_OK && statusCode != HttpStatus.SC_ACCEPTED && statusCode != HttpStatus.SC_NO_CONTENT && statusCode != HttpStatus.SC_CREATED) {
                throw new FireException("Unexpected http status code [" + statusCode + "]");
            }

            
            
            EntityUtils.consume(httpResponse.getEntity());
            return (U) gson.fromJson(jsonResponse, request.getResponseClass());
            
        } catch (IOException ioe) {
            // Also catches ClientProtocolException (from httpClient.execute()) and UnsupportedEncodingException (from response.getEntity()
            logger.error("Exception communicating with Fire.", ioe.getMessage());
            throw new FireException("Exception communicating with Fire.", ioe);
        } finally {
            // Test if response Closeable
            if (httpResponse instanceof Closeable) {
                try {
                    ((Closeable) httpResponse).close();
                } catch (IOException ioe) {
                    // Ignore
                }
            }
        }
    }
    
    /**
     * Internal Use. Perform the actual send of the message.
     *  
     * This will also check if only HTTPS is allowed, based on the {@link HttpConfiguration}, and will 
     * throw a {@link FireException} if HTTP is used when only HTTPS is allowed. A {@link FireException} 
     * is also thrown if the response from Fire is not success (ie. if it's not 200 status code). 
     * 
     * 
     * @param request a prepared request to send
     * @param <T> the class of the request object
     * @param <U> the class of the response object
     * @param accessToken the accessToken to use. 
     * @param httpClient the HttpClient to use.
     * @param httpConfiguration the HttpConfiguration to use. 
     * @return the response to the call. 
     */
    public static <T, U extends Response<U>> U sendGetMessage(Request<T, U> request, String accessToken, HttpClient httpClient, HttpConfiguration httpConfiguration) {
        Gson gson = new GsonBuilder().setDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").create();
        HttpGet httpGet = new HttpGet(httpConfiguration.getEndpoint() + "/" + request.getEndpoint());
        httpGet.addHeader(HttpHeaders.CONTENT_TYPE, ContentType.APPLICATION_JSON.getMimeType());
        
        if (accessToken != null) {
            httpGet.addHeader(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken);
        }
        
        HttpResponse httpResponse = null;

        try {
           
            httpResponse = httpClient.execute(httpGet);

            int statusCode = (httpResponse.getStatusLine().getStatusCode());
            if (statusCode != HttpStatus.SC_OK && statusCode != HttpStatus.SC_ACCEPTED && statusCode != HttpStatus.SC_CREATED) {
                throw new FireException("Unexpected http status code [" + statusCode + "]");
            }

            String jsonResponse = EntityUtils.toString(httpResponse.getEntity());
            
            EntityUtils.consume(httpResponse.getEntity());
            return (U) gson.fromJson(jsonResponse, request.getResponseClass());
            
        } catch (IOException ioe) {
            // Also catches ClientProtocolException (from httpClient.execute()) and UnsupportedEncodingException (from response.getEntity()
            logger.error("Exception communicating with Fire.", ioe.getMessage());
            throw new FireException("Exception communicating with Fire.", ioe);
        } finally {
            // Test if response Closeable
            if (httpResponse instanceof Closeable) {
                try {
                    ((Closeable) httpResponse).close();
                } catch (IOException ioe) {
                    // Ignore
                }
            }
        }
    }
    
    public enum HttpMethod {
        POST,
        GET,
        DELETE,
        PUT
    }
}
