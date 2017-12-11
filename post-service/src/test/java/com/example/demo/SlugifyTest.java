/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package com.example.demo;

import static org.junit.Assert.assertTrue;
import org.junit.Test;

/**
 *
 * @author hantsy
 */
public class SlugifyTest {

    @Test
    public void testSlugify() {
        assertTrue(Utils.slugify("Hello world").equals("hello-world"));
        assertTrue(Utils.slugify("Hello \n world").equals("hello-world"));
    }

}
