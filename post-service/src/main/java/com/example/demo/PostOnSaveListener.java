/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package com.example.demo;

import org.springframework.data.mongodb.core.mapping.event.AbstractMongoEventListener;
import org.springframework.data.mongodb.core.mapping.event.BeforeSaveEvent;
import org.springframework.stereotype.Component;

/**
 *
 * @author hantsy
 */
@Component
public class PostOnSaveListener extends AbstractMongoEventListener<Post> {

    @Override
    public void onBeforeSave(BeforeSaveEvent<Post> event) {
        super.onBeforeSave(event);
        event.getSource().setSlug(Utils.slugify(event.getSource().getTitle()));
    }


}
