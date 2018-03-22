package com.io7m.felixresolver;

import aQute.bnd.osgi.Analyzer;
import aQute.bnd.osgi.Jar;
import aQute.bnd.osgi.resource.ResourceBuilder;
import aQute.bnd.osgi.resource.ResourceUtils;
import org.apache.felix.resolver.Logger;
import org.apache.felix.resolver.ResolverImpl;
import org.ops4j.pax.tinybundles.core.TinyBundle;
import org.ops4j.pax.tinybundles.core.TinyBundles;
import org.osgi.framework.Constants;
import org.osgi.framework.FrameworkUtil;
import org.osgi.framework.InvalidSyntaxException;
import org.osgi.resource.Capability;
import org.osgi.resource.Requirement;
import org.osgi.resource.Resource;
import org.osgi.resource.Wire;
import org.osgi.resource.Wiring;
import org.osgi.service.resolver.HostedCapability;
import org.osgi.service.resolver.ResolutionException;
import org.osgi.service.resolver.ResolveContext;
import org.osgi.service.resolver.Resolver;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class Main
{
  private static final org.slf4j.Logger LOG = LoggerFactory.getLogger(Main.class);

  private Main()
  {

  }

  public static void main(final String[] args)
    throws Exception
  {
    /*
     * Create a couple of example bundles. Bundle b0 depends on a package
     * exported by b1. Bundle b2 depends on a package that doesn't exist.
     */

    final TinyBundle b0 = TinyBundles.bundle();
    b0.symbolicName("com.io7m.b0");
    b0.set("Import-Package", "com.io7m.b1;version=\"1.0.0\"");
    writeBundle(b0, "/tmp/b0.jar");

    final TinyBundle b1 = TinyBundles.bundle();
    b1.symbolicName("com.io7m.b1");
    b1.set("Export-Package", "com.io7m.b1;version=\"1.0.0\"");
    writeBundle(b1, "/tmp/b1.jar");

    final TinyBundle b2 = TinyBundles.bundle();
    b2.symbolicName("com.io7m.b2");
    b2.set("Import-Package", "com.io7m.nonexistent;version=\"1.0.0\"");
    writeBundle(b2, "/tmp/b2.jar");

    /*
     * Use the Bnd API to load the bundles as resources. This is required
     * because the resolver works in terms of resources, capabilities, and
     * requirements.
     */

    final Resource b0_resource = resourceFromFile("/tmp/b0.jar");
    final Resource b1_resource = resourceFromFile("/tmp/b1.jar");
    final Resource b2_resource = resourceFromFile("/tmp/b2.jar");

    {
      /*
       * Create a new resolver context. Add the bundles created above as
       * available resources.
       */

      final Context context = new Context();
      context.resources.add(b0_resource);
      context.resources.add(b1_resource);
      context.resources.add(b2_resource);

      /*
       * Add bundle b0 as a mandatory resource. The resolver is then responsible
       * for working out what other resources are required.
       */

      context.mandatory.add(b0_resource);

      /*
       * Resolve and print the resources.
       */

      final Logger logger = new Logger(4);
      final Resolver resolver = new ResolverImpl(logger);

      final Map<Resource, List<Wire>> results = resolver.resolve(context);
      LOG.debug("results: {}", results);
    }

    /*
     * Now what happens if something cannot be resolved...
     */

    {
      /*
       * Create a new resolver context. Add the bundles created above as
       * available resources.
       */

      final Context context = new Context();
      context.resources.add(b0_resource);
      context.resources.add(b1_resource);
      context.resources.add(b2_resource);

      /*
       * Add bundle b2 as a mandatory resource. The resolver will not be
       * able to resolve it.
       */

      context.mandatory.add(b2_resource);

      /*
       * Resolve and print the resources.
       */

      final Logger logger = new Logger(4);
      final Resolver resolver = new ResolverImpl(logger);

      try {
        resolver.resolve(context);
      } catch (final ResolutionException e) {
        LOG.error("resolution failed");
        e.getUnresolvedRequirements().forEach(requirement -> {
          LOG.debug("unresolved requirement:");
          LOG.debug("  resource: {}", requirement.getResource());
          requirement.getDirectives().forEach((name, value) -> {
            LOG.debug("  unmet requirement: {} {}", name, value);
          });
          requirement.getAttributes().forEach((name, value) -> {
            LOG.debug("  unmet requirement: {} {}", name, value);
          });
        });
      }
    }
  }

  private static Resource resourceFromFile(
    final String file)
    throws Exception
  {
    final ResourceBuilder b0_builder = new ResourceBuilder();
    b0_builder.addFile(
      new File(file),
      new File(file).toURI());
    return b0_builder.build();
  }

  private static void writeBundle(
    final TinyBundle bundle,
    final String file)
    throws IOException
  {
    try (InputStream in = bundle.build()) {
      try (OutputStream out = Files.newOutputStream(Paths.get(file))) {
        in.transferTo(out);
      }
    }
  }

  private static final class Context extends ResolveContext
  {
    private final List<Resource> resources;
    private final Map<Resource, Wiring> wirings;
    private final Collection<Resource> mandatory;
    private final Collection<Resource> optional;

    Context()
    {
      this.resources = new ArrayList<>();
      this.wirings = new HashMap<>();
      this.mandatory = new ArrayList<>();
      this.optional = new ArrayList<>();
    }

    @Override
    public Collection<Resource> getMandatoryResources()
    {
      return new ArrayList<>(this.mandatory);
    }

    @Override
    public Collection<Resource> getOptionalResources()
    {
      return new ArrayList<>(this.optional);
    }

    @Override
    public List<Capability> findProviders(
      final Requirement requirement)
    {
      final List<Capability> capabilities = new ArrayList<>();
      for (final Resource resource : this.resources) {
        for (final Capability capability : resource.getCapabilities(requirement.getNamespace())) {
          if (requirementMatchesCapability(requirement, capability)) {
            capabilities.add(capability);
          }
        }
      }

      return capabilities;
    }

    private static boolean requirementMatchesCapability(
      final Requirement requirement,
      final Capability capability)
    {
      if (requirement == null && capability == null) {
        return true;
      }

      if (requirement == null || capability == null) {
        return false;
      }

      if (!Objects.equals(
        capability.getNamespace(),
        requirement.getNamespace())) {
        return false;
      }

      final String filter = requirement.getDirectives().get(Constants.FILTER_DIRECTIVE);
      if (filter == null) {
        return true;
      }

      try {
        if (FrameworkUtil.createFilter(filter).matches(capability.getAttributes())) {
          return true;
        }
      } catch (final InvalidSyntaxException e) {
        return false;
      }

      return false;
    }

    @Override
    public int insertHostedCapability(
      final List<Capability> capabilities,
      final HostedCapability capability)
    {
      capabilities.add(0, capability);
      return 0;
    }

    @Override
    public boolean isEffective(
      final Requirement requirement)
    {
      return true;
    }

    @Override
    public Map<Resource, Wiring> getWirings()
    {
      return this.wirings;
    }
  }
}
