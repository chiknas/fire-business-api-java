/*
 * Copyright (c) 2001-2019 HPD Software Ltd.
 */

package com.fire.sdk.http;

public class Proxy {
  private String host;
  private int port;

  public Proxy(String host, int port) {
    this.host = host;
    this.port = port;
  }

  String getHost() {
    return host;
  }

  int getPort() {
    return port;
  }
}