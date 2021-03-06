package org.jboss.forge.addon.database.tools.generate;

import java.io.File;
import java.io.FileNotFoundException;
import java.util.ArrayList;
import java.util.Map;
import java.util.Properties;

import javax.inject.Inject;
import javax.persistence.Entity;

import org.jboss.forge.addon.database.tools.connections.ConnectionProfile;
import org.jboss.forge.addon.database.tools.connections.ConnectionProfileManager;
import org.jboss.forge.addon.database.tools.connections.ConnectionProfileManagerProvider;
import org.jboss.forge.addon.database.tools.util.HibernateToolsHelper;
import org.jboss.forge.addon.javaee.jpa.JPAFacet;
import org.jboss.forge.addon.parser.java.facets.JavaSourceFacet;
import org.jboss.forge.addon.parser.java.resources.JavaResource;
import org.jboss.forge.addon.parser.java.resources.JavaResourceVisitor;
import org.jboss.forge.addon.projects.Project;
import org.jboss.forge.addon.projects.ProjectFactory;
import org.jboss.forge.addon.projects.facets.MetadataFacet;
import org.jboss.forge.addon.projects.ui.AbstractProjectCommand;
import org.jboss.forge.addon.resource.FileResource;
import org.jboss.forge.addon.resource.ResourceFactory;
import org.jboss.forge.addon.resource.visit.VisitContext;
import org.jboss.forge.addon.ui.context.UIBuilder;
import org.jboss.forge.addon.ui.context.UIContext;
import org.jboss.forge.addon.ui.context.UIExecutionContext;
import org.jboss.forge.addon.ui.context.UINavigationContext;
import org.jboss.forge.addon.ui.hints.InputType;
import org.jboss.forge.addon.ui.input.UIInput;
import org.jboss.forge.addon.ui.input.UISelectOne;
import org.jboss.forge.addon.ui.metadata.WithAttributes;
import org.jboss.forge.addon.ui.result.NavigationResult;
import org.jboss.forge.addon.ui.result.Result;
import org.jboss.forge.addon.ui.result.Results;
import org.jboss.forge.addon.ui.util.Categories;
import org.jboss.forge.addon.ui.util.Metadata;
import org.jboss.forge.addon.ui.wizard.UIWizard;
import org.jboss.forge.furnace.util.Strings;
import org.jboss.forge.roaster.model.source.JavaSource;

public class GenerateEntitiesCommand extends AbstractProjectCommand implements
         UIWizard
{

   private static String[] COMMAND_CATEGORY = { "Java EE", "JPA" };
   private static String COMMAND_NAME = "JPA: Generate Entities From Tables";
   private static String COMMAND_DESCRIPTION = "Command to generate Java EE entities from database tables.";

   @Inject
   private ProjectFactory projectFactory;

   @Inject
   private ResourceFactory resourceFactory;

   @Inject
   @WithAttributes(
            label = "Target package",
            type = InputType.JAVA_PACKAGE_PICKER,
            description = "The name of the target package in which to generate the entities",
            required = true)
   private UIInput<String> targetPackage;

   @Inject
   @WithAttributes(
            label = "Connection Profile",
            description = "Select the database connection profile you want to use")
   private UISelectOne<String> connectionProfile;

   @Override
   public Metadata getMetadata(UIContext context)
   {
      return Metadata.from(super.getMetadata(context), getClass())
               .name(COMMAND_NAME).description(COMMAND_DESCRIPTION)
               .category(Categories.create(COMMAND_CATEGORY));
   }

   @Inject
   private ConnectionProfileManagerProvider managerProvider;
   private Map<String, ConnectionProfile> profiles;

   @Override
   public void initializeUI(UIBuilder builder) throws Exception
   {
      Project project = getSelectedProject(builder.getUIContext());
      targetPackage.setDefaultValue(calculateModelPackage(project));
      ConnectionProfileManager manager = managerProvider.getConnectionProfileManager();
      profiles = manager.loadConnectionProfiles();
      ArrayList<String> profileNames = new ArrayList<String>();
      profileNames.add("");
      profileNames.addAll(profiles.keySet());
      connectionProfile.setValueChoices(profileNames);
      connectionProfile.setValue("");
      builder.add(targetPackage).add(connectionProfile);
   }

   @Inject
   private GenerateEntitiesCommandDescriptor descriptor;

   @Inject
   private HibernateToolsHelper helper;

   @Override
   public Result execute(UIExecutionContext context)
   {
      return Results.success();
   }

   protected String getParameters()
   {
      return targetPackage.getValue();
   }

   @Override
   protected boolean isProjectRequired()
   {
      return true;
   }

   @Override
   public boolean isEnabled(UIContext context)
   {
      Project project = getSelectedProject(context);
      if (project != null)
      {
         return project.hasFacet(JPAFacet.class) && super.isEnabled(context);
      }
      else
      {
         return false;
      }
   }

   @Override
   public NavigationResult next(UINavigationContext context) throws Exception
   {
      descriptor.targetPackage = targetPackage.getValue();
      descriptor.connectionProfileName = connectionProfile.getValue();
      descriptor.selectedProject = getSelectedProject(context);
      if (Strings.isNullOrEmpty(descriptor.connectionProfileName))
      {
         descriptor.driverClass = null;
         descriptor.urls = null;
         descriptor.connectionProperties = null;
         return Results.navigateTo(ConnectionProfileDetailsStep.class);
      }
      else
      {
         ConnectionProfile profile = profiles.get(descriptor.connectionProfileName);
         if (profile.getPath() != null)
         {
            descriptor.urls = helper.getDriverUrls(createResource(profile.getPath()));
         }
         descriptor.driverClass = profile.getDriver();
         descriptor.connectionProperties = createConnectionProperties(profile);

         return Results.navigateTo(DatabaseTableSelectionStep.class);
      }
   }

   private Properties createConnectionProperties(ConnectionProfile profile)
   {
      Properties result = new Properties();
      result.setProperty("hibernate.connection.driver_class",
               profile.getDriver() == null ? "" : profile.getDriver());
      result.setProperty("hibernate.connection.username",
               profile.getUser() == null ? "" : profile.getUser());
      result.setProperty("hibernate.dialect",
               profile.getDialect() == null ? "" : profile.getDialect());
      result.setProperty("hibernate.connection.password",
               profile.getPassword() == null ? "" : profile.getPassword());
      result.setProperty("hibernate.connection.url",
               profile.getUrl() == null ? "" : profile.getUrl());
      return result;
   }

   /**
    * @param project
    * @return
    */
   private String calculateModelPackage(Project project)
   {
      final String[] value = new String[1];
      project.getFacet(JavaSourceFacet.class).visitJavaSources(new JavaResourceVisitor()
      {
         @Override
         public void visit(VisitContext context, JavaResource javaResource)
         {
            try
            {
               JavaSource<?> javaSource = javaResource.getJavaType();
               if (javaSource.hasAnnotation(Entity.class))
               {
                  value[0] = javaSource.getPackage();
               }
            }
            catch (FileNotFoundException ignore)
            {
            }
         }
      });
      if (value[0] == null)
      {
         value[0] = project.getFacet(MetadataFacet.class).getTopLevelPackage() + ".model";
      }
      return value[0];
   }

   @Override
   protected ProjectFactory getProjectFactory()
   {
      return projectFactory;
   }

   @SuppressWarnings("unchecked")
   private FileResource<?> createResource(String fullPath)
   {
      return resourceFactory.create(FileResource.class, new File(fullPath));
   }
}
