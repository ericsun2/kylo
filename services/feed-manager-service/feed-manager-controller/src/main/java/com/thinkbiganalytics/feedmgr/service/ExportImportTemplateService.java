package com.thinkbiganalytics.feedmgr.service;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.thinkbiganalytics.feedmgr.nifi.NifiTemplateParser;
import com.thinkbiganalytics.feedmgr.rest.model.RegisteredTemplate;
import com.thinkbiganalytics.feedmgr.rest.support.SystemNamingService;
import com.thinkbiganalytics.json.ObjectMapperSerializer;
import com.thinkbiganalytics.nifi.rest.client.NifiRestClient;
import com.thinkbiganalytics.nifi.rest.model.NifiProcessGroup;
import com.thinkbiganalytics.nifi.rest.model.NifiProcessorDTO;
import com.thinkbiganalytics.rest.JerseyClientException;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.nifi.web.api.dto.TemplateDTO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;
import org.xml.sax.SAXException;

import javax.xml.parsers.ParserConfigurationException;
import javax.xml.xpath.XPathExpressionException;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringWriter;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Created by sr186054 on 5/6/16.
 */
public class ExportImportTemplateService {

    private static final String NIFI_TEMPLATE_XML_FILE = "nifiTemplate.xml";

    private static final String TEMPLATE_JSON_FILE = "template.json";


    @Autowired
    MetadataService metadataService;

    @Autowired
    NifiRestClient nifiRestClient;

    public class ExportTemplate {
        private String fileName;
        private byte[] file;

        public ExportTemplate(String fileName, byte[] file) {
            this.fileName = fileName;
            this.file = file;
        }

        public String getFileName() {
            return fileName;
        }

        public byte[] getFile() {
            return file;
        }
    }

    public class ImportTemplate {
        String fileName;
        String templateName;
        boolean success;
        private NifiProcessGroup templateResults;
        private List<NifiProcessorDTO> controllerServiceErrors;

        private boolean zipFile;

        public ImportTemplate(String fileName) {
            this.fileName = fileName;
        }

        private String nifiTemplateXml;
        private String templateJson;

        @JsonIgnore
        public boolean isValid() {
            return StringUtils.isNotBlank(templateJson) && StringUtils.isNotBlank(nifiTemplateXml);
        }

        public ImportTemplate(String templateName, boolean success) {
            this.templateName = templateName;
            this.success = success;
        }

        public String getTemplateName() {
            return templateName;
        }

        public void setTemplateName(String templateName) {
            this.templateName = templateName;
        }

        public boolean isSuccess() {
            return success;
        }

        public void setSuccess(boolean success) {
            this.success = success;
        }

        public String getNifiTemplateXml() {
            return nifiTemplateXml;
        }

        public void setNifiTemplateXml(String nifiTemplateXml) {
            this.nifiTemplateXml = nifiTemplateXml;
        }

        public String getTemplateJson() {
            return templateJson;
        }

        public void setTemplateJson(String templateJson) {
            this.templateJson = templateJson;
        }

        public NifiProcessGroup getTemplateResults() {
            return templateResults;
        }

        public void setTemplateResults(NifiProcessGroup templateResults) {
            this.templateResults = templateResults;
            inspectForControllerServiceErrors();
        }


        public String getFileName() {
            return fileName;
        }

        public boolean isZipFile() {
            return zipFile;
        }

        public void setZipFile(boolean zipFile) {
            this.zipFile = zipFile;
        }

        private void inspectForControllerServiceErrors() {
            if (templateResults != null) {
                List<NifiProcessorDTO> errors = templateResults.getControllerServiceErrors();
                this.controllerServiceErrors = errors;
            }
        }

        public List<NifiProcessorDTO> getControllerServiceErrors() {
            return controllerServiceErrors;
        }
    }

    public ExportTemplate exportTemplate(String templateId) {
        RegisteredTemplate template = metadataService.getRegisteredTemplate(templateId);
        if (template != null) {
            String templateXml = null;
            try {
                if (template != null) {
                    try {
                        templateXml = nifiRestClient.getTemplateXml(template.getNifiTemplateId());
                    } catch (JerseyClientException e) {
                        TemplateDTO templateDTO = nifiRestClient.getTemplateByName(template.getTemplateName());
                        if (templateDTO != null) {
                            templateXml = nifiRestClient.getTemplateXml(templateDTO.getId());
                        }
                    }
                }
            } catch (JerseyClientException e) {
                throw new UnsupportedOperationException("Unable to find Nifi Template for " + templateId);
            }

            //create a zip file with the template and xml
            byte[] zipFile = zip(template, templateXml);

            return new ExportTemplate(SystemNamingService.generateSystemName(template.getTemplateName()) + ".zip", zipFile);

        } else {
            throw new UnsupportedOperationException("Unable to find Template for " + templateId);
        }
    }

    private byte[] zip(RegisteredTemplate template, String nifiTemplateXml) {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(baos)) {

            ZipEntry entry = new ZipEntry(NIFI_TEMPLATE_XML_FILE);
            zos.putNextEntry(entry);
            zos.write(nifiTemplateXml.getBytes());
            zos.closeEntry();
            entry = new ZipEntry(TEMPLATE_JSON_FILE);
            zos.putNextEntry(entry);
            String json = ObjectMapperSerializer.serialize(template);
            zos.write(json.getBytes());
            zos.closeEntry();


  /* use more Entries to add more files
     and use closeEntry() to close each file entry */

        } catch (IOException ioe) {
            ioe.printStackTrace();
        }
        return baos.toByteArray();
    }

    private boolean isValidFileImport(String fileName) {
        return fileName.endsWith(".zip") || fileName.endsWith(".xml");
    }

    private ImportTemplate importZip(String fileName, InputStream inputStream, boolean overwrite, boolean createReusableFlow) throws JerseyClientException, IOException {
        ImportTemplate importTemplate = openZip(fileName, inputStream);
        RegisteredTemplate template = ObjectMapperSerializer.deserialize(importTemplate.getTemplateJson(), RegisteredTemplate.class);

        //1 ensure this template doesnt already exist
        importTemplate.setTemplateName(template.getTemplateName());
        RegisteredTemplate existingTemplate = metadataService.getRegisteredTemplateByName(template.getTemplateName());
        if (existingTemplate != null) {

            if (!overwrite) {
                throw new UnsupportedOperationException("Unable to import the template " + template.getTemplateName() + " because it is already registered.  Please click the overwrite box and try again");
            }
            template.setId(existingTemplate.getId());
        } else {
            template.setId(null);
        }


        //Check to see if this template doesnt already exist in Nifi
        String templateName = null;
        String oldTemplateXml = null;

        try {
            templateName = NifiTemplateParser.getTemplateName(importTemplate.getNifiTemplateXml());
            importTemplate.setTemplateName(templateName);
            TemplateDTO templateDTO = nifiRestClient.getTemplateByName(templateName);
            if (templateDTO != null) {
                if (overwrite) {
                    oldTemplateXml = nifiRestClient.getTemplateXml(templateDTO.getId());
                    nifiRestClient.deleteTemplate(templateDTO.getId());
                } else {
                    throw new UnsupportedOperationException("Unable to import Template " + templateName + ".  It already exists in Nifi.  Please check that you wish to overwrite this template and try to import again.");
                }
            }
        } catch (ParserConfigurationException | XPathExpressionException | SAXException e) {
            throw new UnsupportedOperationException("The Xml File you are trying to import is not a valid Nifi Template.  Please Try again. " + e.getMessage());
        }


        TemplateDTO dto = nifiRestClient.importTemplate(template.getTemplateName(), importTemplate.getNifiTemplateXml());
        template.setNifiTemplateId(dto.getId());
        //register it in the system
        metadataService.registerTemplate(template);
        //get the new template
        template = metadataService.getRegisteredTemplateByName(template.getTemplateName());
        NifiProcessGroup newTemplateInstance = nifiRestClient.createNewTemplateInstance(template.getNifiTemplateId(), createReusableFlow);
        if (newTemplateInstance.isSuccess()) {
            importTemplate.setSuccess(true);
        } else {
            //delete this template
            importTemplate.setSuccess(false);
            //if it doesnt have any feeds delete it
            metadataService.deleteRegisteredTemplate(template.getId());
            //restore old template
            if (oldTemplateXml != null) {
                nifiRestClient.importTemplate(oldTemplateXml);
            }
            //restore old registered template
            if (existingTemplate != null) {
                metadataService.registerTemplate(existingTemplate);
            }
        }
        importTemplate.setTemplateResults(newTemplateInstance);
        //delete processgroup
        nifiRestClient.deleteProcessGroup(newTemplateInstance.getProcessGroupEntity().getProcessGroup());


        return importTemplate;
    }

    private ImportTemplate importNifiTemplate(String fileName, InputStream xmlFile, boolean overwrite, boolean createReusableFlow) throws IOException, JerseyClientException {
        ImportTemplate importTemplate = new ImportTemplate(fileName);
        StringWriter writer = new StringWriter();
        IOUtils.copy(xmlFile, writer, "UTF-8");
        String xmlTemplate = writer.toString();
        importTemplate.setNifiTemplateXml(xmlTemplate);

        String oldTemplateXml = null;

        //Check to see if this template doesnt already exist in Nifi
        String templateName = null;
        try {
            templateName = NifiTemplateParser.getTemplateName(xmlTemplate);
            importTemplate.setTemplateName(templateName);
            TemplateDTO templateDTO = nifiRestClient.getTemplateByName(templateName);
            if (templateDTO != null) {
                if (overwrite) {
                    oldTemplateXml = nifiRestClient.getTemplateXml(templateDTO.getId());
                    nifiRestClient.deleteTemplate(templateDTO.getId());
                } else {
                    throw new UnsupportedOperationException("Unable to import Template " + templateName + ".  It already exists in Nifi.  Please check that you wish to overwrite this template and try to import again.");
                }
            }
        } catch (ParserConfigurationException | XPathExpressionException | SAXException e) {
            throw new UnsupportedOperationException("The Xml File you are trying to import is not a valid Nifi Template.  Please Try again. " + e.getMessage());
        }

        TemplateDTO dto = nifiRestClient.importTemplate(xmlTemplate);

        NifiProcessGroup newTemplateInstance = nifiRestClient.createNewTemplateInstance(dto.getId(), createReusableFlow);
        if (newTemplateInstance.isSuccess()) {
            importTemplate.setSuccess(true);
        } else {
            //delete this template
            importTemplate.setSuccess(false);
            //delete the template?
            nifiRestClient.deleteTemplate(dto.getId());
            nifiRestClient.deleteProcessGroup(newTemplateInstance.getProcessGroupEntity().getProcessGroup());
            //restore old template
            if (oldTemplateXml != null) {
                nifiRestClient.importTemplate(oldTemplateXml);
            }
        }
        importTemplate.setTemplateResults(newTemplateInstance);
        //delete processgroup
        if (!createReusableFlow) {
            nifiRestClient.deleteProcessGroup(newTemplateInstance.getProcessGroupEntity().getProcessGroup());
        }
        return importTemplate;
    }


    @Transactional(transactionManager = "metadataTransactionManager")
    public ImportTemplate importTemplate(String fileName, InputStream inputStream, boolean overwrite, boolean createReusableFlow) {
        ImportTemplate template = null;
        if (!isValidFileImport(fileName)) {
            throw new UnsupportedOperationException("Unable to import " + fileName + ".  The file must be a zip file or a Nifi Template xml file");
        }

        try {
            if (fileName.endsWith(".zip")) {
                template = importZip(fileName, inputStream, overwrite, false); //dont allow exported reusable flows to become registered templates
            } else if (fileName.endsWith(".xml")) {
                template = importNifiTemplate(fileName, inputStream, overwrite, createReusableFlow);
            }
        } catch (IOException | JerseyClientException e) {
            throw new UnsupportedOperationException("Error importing template  " + fileName + ".  " + e.getMessage());
        }
        return template;
    }

    private ImportTemplate openZip(String fileName, InputStream inputStream) throws IOException {
        byte[] buffer = new byte[1024];
        ZipInputStream zis = new ZipInputStream(inputStream);
        ZipEntry entry;
        // while there are entries I process them
        ImportTemplate importTemplate = new ImportTemplate(fileName);
        while ((entry = zis.getNextEntry()) != null) {
            System.out.println("entry: " + entry.getName() + ", " + entry.getSize());
            // consume all the data from this entry
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            int len = 0;
            while ((len = zis.read(buffer)) > 0) {
                out.write(buffer, 0, len);
            }
            out.close();
            String outString = new String(out.toByteArray(), "UTF-8");
            if (entry.getName().startsWith(NIFI_TEMPLATE_XML_FILE)) {
                importTemplate.setNifiTemplateXml(outString);
            } else if (entry.getName().startsWith(TEMPLATE_JSON_FILE)) {
                importTemplate.setTemplateJson(outString);
            }

            //while (zis.available() > 0)
            //  zis.read();

        }
        zis.closeEntry();
        zis.close();
        if (!importTemplate.isValid()) {
            throw new UnsupportedOperationException(" The file you uploaded is not a valid archive.  Please ensure the Zip file has been exported from the system and has 2 valid files named: " + NIFI_TEMPLATE_XML_FILE + ", and " + TEMPLATE_JSON_FILE);
        }
        importTemplate.setZipFile(true);
        return importTemplate;

    }


}