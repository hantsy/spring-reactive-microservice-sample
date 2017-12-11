/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package com.example.demo;

/**
 *
 * @author hantsy
 */
public final class Utils {

    private Utils() {
    }

    public static String slugify(String source) {
        String result = source.toLowerCase();
        result = result.replaceAll("\r\n", "");
        result = result.replaceAll("\n", "");
        result = result.replaceAll("\r", "");
        result = result.replaceAll("[\\s]+", "-");
        return result;
    }

}
