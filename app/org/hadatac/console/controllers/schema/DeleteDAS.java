package org.hadatac.console.controllers.schema;

import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Iterator;

import play.data.Form;
import play.mvc.Controller;
import play.mvc.Result;

import org.hadatac.console.controllers.AuthApplication;
import org.hadatac.console.models.DataAcquisitionSchemaForm;
import org.hadatac.console.views.html.schema.*;
import org.hadatac.entity.pojo.DataAcquisitionSchema;

import be.objectify.deadbolt.java.actions.Group;
import be.objectify.deadbolt.java.actions.Restrict;

public class DeleteDAS extends Controller {

	@Restrict(@Group(AuthApplication.DATA_OWNER_ROLE))
        public static Result index(String das_uri) {
	    DataAcquisitionSchemaForm dasForm = new DataAcquisitionSchemaForm();
	    DataAcquisitionSchema das = null;
	    
	    try {
		if (das_uri != null) {
		    das_uri = URLDecoder.decode(das_uri, "UTF-8");
		} else {
		    das_uri = "";
		}
	    } catch (UnsupportedEncodingException e) {
		e.printStackTrace();
	    }
	    
	    if (!das_uri.equals("")) {
		
		das = DataAcquisitionSchema.find(das_uri);
		System.out.println("delete data acquisition schema");
		return ok(deleteDAS.render(das_uri, dasForm));
	    }
	    return ok(deleteDAS.render(das_uri, dasForm));
	}
    
    @Restrict(@Group(AuthApplication.DATA_OWNER_ROLE))
	public static Result postIndex(String das_uri) {
        return index(das_uri);
    }
    
    @Restrict(@Group(AuthApplication.DATA_OWNER_ROLE))
	public static Result processForm(String das_uri) {
        DataAcquisitionSchema das = null;
	
        try {
            if (das_uri != null) {
                das_uri = URLDecoder.decode(das_uri, "UTF-8");
            } else {
                das_uri = "";
            }
        } catch (UnsupportedEncodingException e) {
            e.printStackTrace();
        }
	
        if (!das_uri.equals("")) {
            das = DataAcquisitionSchema.find(das_uri);
        }
	
        Form<DataAcquisitionSchemaForm> form = Form.form(DataAcquisitionSchemaForm.class).bindFromRequest();
        DataAcquisitionSchemaForm data = form.get();
	
	if (das != null) {
	    das.delete();
	}
	
        if (form.hasErrors()) {
            return badRequest(DASConfirm.render("ERROR Deleting Data Acquisition Schema", data));
        } else {
            return ok(DASConfirm.render("Delete Data Acquisition Schema", data));
        }
    }
}