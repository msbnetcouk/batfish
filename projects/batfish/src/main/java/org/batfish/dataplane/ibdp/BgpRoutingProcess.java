package org.batfish.dataplane.ibdp;

import static com.google.common.base.MoreObjects.firstNonNull;
import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;
import static org.batfish.common.util.CollectionUtil.toImmutableSortedMap;
import static org.batfish.common.util.CollectionUtil.toOrderedHashCode;
import static org.batfish.datamodel.BgpRoute.DEFAULT_LOCAL_PREFERENCE;
import static org.batfish.datamodel.MultipathEquivalentAsPathMatchMode.EXACT_PATH;
import static org.batfish.datamodel.OriginMechanism.GENERATED;
import static org.batfish.datamodel.OriginMechanism.NETWORK;
import static org.batfish.datamodel.OriginMechanism.REDISTRIBUTE;
import static org.batfish.datamodel.routing_policy.Environment.Direction.IN;
import static org.batfish.datamodel.routing_policy.Environment.Direction.OUT;
import static org.batfish.dataplane.protocols.BgpProtocolHelper.toBgpv4Route;
import static org.batfish.dataplane.protocols.BgpProtocolHelper.transformBgpRouteOnImport;
import static org.batfish.dataplane.rib.RibDelta.importDeltaToBuilder;
import static org.batfish.dataplane.rib.RibDelta.importRibDelta;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Multimap;
import com.google.common.collect.MultimapBuilder;
import com.google.common.collect.Sets;
import com.google.common.collect.Streams;
import com.google.common.graph.ValueGraph;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;
import java.util.SortedMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.ParametersAreNonnullByDefault;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.batfish.datamodel.AbstractRoute;
import org.batfish.datamodel.AbstractRouteDecorator;
import org.batfish.datamodel.AnnotatedRoute;
import org.batfish.datamodel.BgpActivePeerConfig;
import org.batfish.datamodel.BgpAdvertisement;
import org.batfish.datamodel.BgpAdvertisement.BgpAdvertisementType;
import org.batfish.datamodel.BgpPeerConfig;
import org.batfish.datamodel.BgpPeerConfigId;
import org.batfish.datamodel.BgpProcess;
import org.batfish.datamodel.BgpRoute;
import org.batfish.datamodel.BgpSessionProperties;
import org.batfish.datamodel.BgpTieBreaker;
import org.batfish.datamodel.BgpVrfLeakConfig;
import org.batfish.datamodel.Bgpv4Route;
import org.batfish.datamodel.Configuration;
import org.batfish.datamodel.EvpnRoute;
import org.batfish.datamodel.EvpnType3Route;
import org.batfish.datamodel.EvpnType5Route;
import org.batfish.datamodel.GeneratedRoute;
import org.batfish.datamodel.GenericRibReadOnly;
import org.batfish.datamodel.Ip;
import org.batfish.datamodel.MultipathEquivalentAsPathMatchMode;
import org.batfish.datamodel.NetworkConfigurations;
import org.batfish.datamodel.OriginMechanism;
import org.batfish.datamodel.OriginType;
import org.batfish.datamodel.Prefix;
import org.batfish.datamodel.PrefixTrieMultiMap;
import org.batfish.datamodel.Route;
import org.batfish.datamodel.RoutingProtocol;
import org.batfish.datamodel.Vrf;
import org.batfish.datamodel.bgp.AddressFamily;
import org.batfish.datamodel.bgp.AddressFamily.Type;
import org.batfish.datamodel.bgp.BgpAggregate;
import org.batfish.datamodel.bgp.BgpTopology;
import org.batfish.datamodel.bgp.BgpTopology.EdgeId;
import org.batfish.datamodel.bgp.RouteDistinguisher;
import org.batfish.datamodel.bgp.community.ExtendedCommunity;
import org.batfish.datamodel.dataplane.rib.RibGroup;
import org.batfish.datamodel.route.nh.NextHopDiscard;
import org.batfish.datamodel.route.nh.NextHopVrf;
import org.batfish.datamodel.routing_policy.Environment.Direction;
import org.batfish.datamodel.routing_policy.RoutingPolicy;
import org.batfish.datamodel.routing_policy.communities.CommunitySet;
import org.batfish.datamodel.vxlan.Layer2Vni;
import org.batfish.dataplane.ibdp.VirtualRouter.RibExprEvaluator;
import org.batfish.dataplane.protocols.BgpProtocolHelper;
import org.batfish.dataplane.protocols.GeneratedRouteHelper;
import org.batfish.dataplane.rib.BgpRib;
import org.batfish.dataplane.rib.Bgpv4Rib;
import org.batfish.dataplane.rib.EvpnRib;
import org.batfish.dataplane.rib.Rib;
import org.batfish.dataplane.rib.RibDelta;
import org.batfish.dataplane.rib.RibDelta.Builder;
import org.batfish.dataplane.rib.RouteAdvertisement;
import org.batfish.dataplane.rib.RouteAdvertisement.Reason;

/**
 * BGP routing process. A dataplane counter-part of {@link BgpProcess}. Maintains state necessary
 * for exchange of BGP routing messages.
 */
@ParametersAreNonnullByDefault
final class BgpRoutingProcess implements RoutingProcess<BgpTopology, BgpRoute<?, ?>> {
  /** Configuration for this process */
  @Nonnull private final BgpProcess _process;
  /** Parent node configuration */
  @Deprecated @Nonnull private final Configuration _c;

  /**
   * Whether routes should be exported from the BGP RIB (as opposed to directly from the main RIB).
   *
   * <p>For those familiar with vendor semantics: if set to {@code true}, the BGP routing process
   * models IOS behavior, and the BGP RIB will contain all active BGP routes (including local routes
   * resulting from redistribution, network statements, and aggregate routes). If set to {@code
   * false}, the routing process follows the Juniper model, where routes are redistributed +
   * exported directly from the main RIB.
   */
  private final boolean _exportFromBgpRib;

  @Nonnull private final PrefixTrieMultiMap<BgpAggregate> _aggregates;

  @Nonnull private final RoutingPolicies _policies;
  @Nonnull private final String _hostname;
  /** Name of our VRF */
  @Nonnull private final String _vrfName;
  /** Reference to the parent {@link VirtualRouter} main RIB (read-only). */
  @Nonnull private final GenericRibReadOnly<AnnotatedRoute<AbstractRoute>> _mainRib;
  /** Current BGP topology */
  @Nonnull private BgpTopology _topology;
  /** Metadata about propagated prefixes to/from neighbors */
  @Nonnull private PrefixTracer _prefixTracer;

  /** Route dependency tracker for BGP IPv4 aggregate routes */
  @Nonnull
  BgpRouteDependencyTracker<Bgpv4Route, AbstractRoute> _bgpAggDeps =
      new BgpRouteDependencyTracker<>();
  /** All BGP neighbor that speaks IPv4 unicast address family that we know of */
  @Nonnull ImmutableSortedSet<EdgeId> _bgpv4Edges;
  /**
   * Incoming EVPN type 3 advertisements into this router from each BGP neighbor that speaks EVPN
   * address family
   */
  @Nonnull @VisibleForTesting
  SortedMap<EdgeId, Queue<RouteAdvertisement<EvpnType3Route>>> _evpnType3IncomingRoutes;

  /**
   * External BGP announcements to be processed upon the first iteration of BGP on this node.
   *
   * <p>Always null after that first iteration.
   */
  @Nullable Set<BgpAdvertisement> _externalAdvertisements;

  // RIBs and RIB delta builders
  /** Helper RIB containing all paths obtained with external BGP, for IPv4 unicast */
  @Nonnull final Bgpv4Rib _ebgpv4Rib;
  /** RIB containing paths obtained with iBGP, for IPv4 unicast */
  @Nonnull final Bgpv4Rib _ibgpv4Rib;

  // outgoing RIB deltas for the current round (i.e., deltas generated in the previous round)
  @Nonnull private RibDelta<Bgpv4Route> _ebgpv4DeltaPrev = RibDelta.empty();
  @Nonnull private RibDelta<Bgpv4Route> _ebgpv4DeltaPrevBestPath = RibDelta.empty();
  @Nonnull private RibDelta<Bgpv4Route> _bgpv4DeltaPrev = RibDelta.empty();
  @Nonnull private RibDelta<Bgpv4Route> _bgpv4DeltaPrevBestPath = RibDelta.empty();

  // copy of RIBs from prev round, for new links in the current round.
  private @Nonnull Set<Bgpv4Route> _ebgpv4Prev;
  private @Nonnull Set<Bgpv4Route> _ebgpv4PrevBestPath;
  private @Nonnull Set<Bgpv4Route> _bgpv4Prev;
  private @Nonnull Set<Bgpv4Route> _bgpv4PrevBestPath;

  /**
   * Routes in the main RIB at the end of the previous round. Unused if {@link #_exportFromBgpRib}
   * is set.
   */
  private @Nonnull Set<AnnotatedRoute<AbstractRoute>> _mainRibPrev;

  /** Combined BGP (both iBGP and eBGP) RIB, for IPv4 unicast */
  @Nonnull Bgpv4Rib _bgpv4Rib;
  /** {@link RibDelta} representing changes to {@link #_bgpv4Rib} in the current iteration */
  @Nonnull private Builder<Bgpv4Route> _bgpv4DeltaBuilder = RibDelta.builder();
  /** {@link RibDelta} representing changes to {@link #_ebgpv4Rib} in the current iteration */
  @Nonnull private Builder<Bgpv4Route> _ebgpv4DeltaBuilder = RibDelta.builder();
  /**
   * {@link RibDelta} representing changes to {@link #_bgpv4Rib} best path routes in the current
   * iteration
   */
  @Nonnull private Builder<Bgpv4Route> _bgpv4DeltaBestPathBuilder = RibDelta.builder();
  /**
   * {@link RibDelta} representing changes to {@link #_ebgpv4Rib} best path routes in the current
   * iteration
   */
  @Nonnull private Builder<Bgpv4Route> _ebgpv4DeltaBestPathBuilder = RibDelta.builder();
  /**
   * Keep track of routes we had imported from other VRF during leaking, to avoid exporting them
   * again (chain leaking).
   */
  @Nonnull private final Set<Bgpv4Route> _importedFromOtherVrfs = new HashSet<>(0);

  /** eBGP RIB for EVPN type 3 routes */
  @Nonnull private EvpnRib<EvpnType3Route> _ebgpType3EvpnRib;
  /** iBGP RIB for EVPN type 3 routes */
  @Nonnull private EvpnRib<EvpnType3Route> _ibgpType3EvpnRib;
  /** Combined RIB for EVPN type 3 routes */
  @Nonnull private EvpnRib<EvpnType3Route> _evpnType3Rib;
  /** eBGP RIB for EVPN type 5 routes */
  @Nonnull private EvpnRib<EvpnType5Route> _ebgpType5EvpnRib;
  /** iBGP RIB for EVPN type 5 routes */
  @Nonnull private EvpnRib<EvpnType5Route> _ibgpType5EvpnRib;
  /** Combined RIB for EVPN type 5 routes */
  @Nonnull private EvpnRib<EvpnType5Route> _evpnType5Rib;

  /** Combined EVPN RIB for e/iBGP across all route types */
  @Nonnull EvpnRib<EvpnRoute<?, ?>> _evpnRib;
  /** Builder for constructing {@link RibDelta} for routes in {@link #_evpnRib} */
  @Nonnull private Builder<EvpnRoute<?, ?>> _evpnDeltaBuilder = RibDelta.builder();

  /** Keep track of EVPN type 3 routes initialized from our own VNI settings */
  @Nonnull private RibDelta<EvpnType3Route> _evpnInitializationDelta;

  /** Delta builder for routes that must be propagated to the main RIB */
  @Nonnull private RibDelta.Builder<BgpRoute<?, ?>> _toMainRib = RibDelta.builder();

  /* Indicates whether this BGP process has been initialized. */
  private boolean _initialized = false;

  /**
   * Mapping from extended community route target patterns to VRF name. Used for determining where
   * to merge EVPN routes
   */
  @Nonnull private final Map<String, String> _rtVrfMapping;

  /** Changed main RIB routes to be redistributed. Unused if {@link #_exportFromBgpRib} is set. */
  @Nonnull private RibDelta<AnnotatedRoute<AbstractRoute>> _mainRibDelta;

  /** Set of edges (sessions) that came up since previous topology update */
  private Set<EdgeId> _evpnEdgesWentUp = ImmutableSet.of();

  /** Set of edges (sessions) that came up since previous topology update */
  private Set<EdgeId> _unicastEdgesWentUp = ImmutableSet.of();

  /**
   * Type 3 routes that were created locally (across all VRFs). Save them so that if new sessions
   * come up, we can easily send out the updates
   */
  @Nonnull private RibDelta<EvpnType3Route> _localType3Routes = RibDelta.empty();

  @Nonnull private final RibExprEvaluator _ribExprEvaluator;

  private static final Logger LOGGER = LogManager.getLogger(BgpRoutingProcess.class);

  /**
   * Create a new BGP process
   *
   * @param process the {@link BgpProcess} -- configuration for this routing process
   * @param configuration the parent {@link Configuration}
   * @param vrfName name of the VRF this process is in
   * @param mainRib take in a reference to MainRib for read-only use (e.g., getting IGP cost to
   */
  BgpRoutingProcess(
      BgpProcess process,
      Configuration configuration,
      String vrfName,
      Rib mainRib,
      BgpTopology topology,
      PrefixTracer prefixTracer) {
    _process = process;
    _c = configuration;
    _hostname = configuration.getHostname();
    _policies = RoutingPolicies.from(configuration);
    _vrfName = vrfName;
    _mainRib = mainRib;
    _topology = topology;
    _prefixTracer = prefixTracer;

    _exportFromBgpRib = configuration.getExportBgpFromBgpRib();

    // Message queues start out empty
    _bgpv4Edges = ImmutableSortedSet.of();
    _evpnType3IncomingRoutes = ImmutableSortedMap.of();

    // Initialize all RIBs
    BgpTieBreaker bestPathTieBreaker =
        firstNonNull(_process.getTieBreaker(), BgpTieBreaker.ARRIVAL_ORDER);
    MultipathEquivalentAsPathMatchMode multiPathMatchMode =
        firstNonNull(_process.getMultipathEquivalentAsPathMatchMode(), EXACT_PATH);
    boolean clusterListAsIbgpCost = _process.getClusterListAsIbgpCost();
    _ebgpv4Rib =
        new Bgpv4Rib(
            _mainRib,
            bestPathTieBreaker,
            _process.getMultipathEbgp() ? null : 1,
            multiPathMatchMode,
            clusterListAsIbgpCost,
            _process.getLocalOriginationTypeTieBreaker(),
            _process.getNetworkNextHopIpTieBreaker(),
            _process.getRedistributeNextHopIpTieBreaker());
    _ibgpv4Rib =
        new Bgpv4Rib(
            _mainRib,
            bestPathTieBreaker,
            _process.getMultipathIbgp() ? null : 1,
            multiPathMatchMode,
            clusterListAsIbgpCost,
            _process.getLocalOriginationTypeTieBreaker(),
            _process.getNetworkNextHopIpTieBreaker(),
            _process.getRedistributeNextHopIpTieBreaker());
    _bgpv4Rib =
        new Bgpv4Rib(
            _mainRib,
            bestPathTieBreaker,
            _process.getMultipathEbgp() || _process.getMultipathIbgp() ? null : 1,
            multiPathMatchMode,
            clusterListAsIbgpCost,
            _process.getLocalOriginationTypeTieBreaker(),
            _process.getNetworkNextHopIpTieBreaker(),
            _process.getRedistributeNextHopIpTieBreaker());

    _mainRibDelta = RibDelta.empty();

    // EVPN Ribs
    _ebgpType3EvpnRib =
        new EvpnRib<>(
            _mainRib,
            bestPathTieBreaker,
            null,
            multiPathMatchMode,
            clusterListAsIbgpCost,
            _process.getLocalOriginationTypeTieBreaker());
    _ibgpType3EvpnRib =
        new EvpnRib<>(
            _mainRib,
            bestPathTieBreaker,
            null,
            multiPathMatchMode,
            clusterListAsIbgpCost,
            _process.getLocalOriginationTypeTieBreaker());
    _evpnType3Rib =
        new EvpnRib<>(
            _mainRib,
            bestPathTieBreaker,
            null,
            multiPathMatchMode,
            clusterListAsIbgpCost,
            _process.getLocalOriginationTypeTieBreaker());
    /*
     TODO: type5 RIBs are currently unused. Correct implementation blocked on having local bgp
       ribs
    */
    _ebgpType5EvpnRib =
        new EvpnRib<>(
            _mainRib,
            bestPathTieBreaker,
            null,
            multiPathMatchMode,
            clusterListAsIbgpCost,
            _process.getLocalOriginationTypeTieBreaker());
    _ibgpType5EvpnRib =
        new EvpnRib<>(
            _mainRib,
            bestPathTieBreaker,
            null,
            multiPathMatchMode,
            clusterListAsIbgpCost,
            _process.getLocalOriginationTypeTieBreaker());
    _evpnType5Rib =
        new EvpnRib<>(
            _mainRib,
            bestPathTieBreaker,
            null,
            multiPathMatchMode,
            clusterListAsIbgpCost,
            _process.getLocalOriginationTypeTieBreaker());
    _evpnRib =
        new EvpnRib<>(
            _mainRib,
            bestPathTieBreaker,
            null,
            multiPathMatchMode,
            clusterListAsIbgpCost,
            _process.getLocalOriginationTypeTieBreaker());
    _evpnInitializationDelta = RibDelta.empty();
    _rtVrfMapping = computeRouteTargetToVrfMap(getAllPeerConfigs(_process));
    assert _rtVrfMapping != null; // Avoid unused warning
    _ribExprEvaluator = new RibExprEvaluator(_mainRib);
    _aggregates = new PrefixTrieMultiMap<>();
    _process.getAggregates().forEach(_aggregates::put);

    _mainRibPrev = ImmutableSet.of();
    _bgpv4Prev = ImmutableSet.of();
    _bgpv4PrevBestPath = ImmutableSet.of();
    _ebgpv4Prev = ImmutableSet.of();
    _ebgpv4PrevBestPath = ImmutableSet.of();
  }

  /**
   * Computes the mapping route targets to VRF names, for all layer 3 EVPN VNIs, across all bgp
   * neighbors in our VRF.
   */
  @VisibleForTesting
  static Map<String, String> computeRouteTargetToVrfMap(Stream<BgpPeerConfig> peerConfigs) {
    HashMap<String, String> rtVrfMappingBuilder = new HashMap<>();
    peerConfigs
        .map(BgpPeerConfig::getEvpnAddressFamily)
        .filter(Objects::nonNull)
        .flatMap(af -> Stream.concat(af.getL3VNIs().stream(), af.getL2VNIs().stream()))
        .forEach(vni -> rtVrfMappingBuilder.put(vni.getImportRouteTarget(), vni.getVrf()));
    return ImmutableMap.copyOf(rtVrfMappingBuilder);
  }

  @Override
  public void initialize(Node n) {
    _initialized = true;
    initLocalEvpnRoutes(n);
  }

  /** Returns true if this process has been initialized */
  public boolean isInitialized() {
    return _initialized;
  }

  /**
   * Initialize incoming BGP message queues for all address families.
   *
   * @param bgpTopology source of truth for which sessions get established.
   */
  private void initBgpQueues(BgpTopology bgpTopology) {
    ValueGraph<BgpPeerConfigId, BgpSessionProperties> graph = bgpTopology.getGraph();
    // Create incoming message queues for sessions that exchange IPv4 unicast info
    _bgpv4Edges =
        getEdgeIdStream(graph, BgpPeerConfig::getIpv4UnicastAddressFamily, Type.IPV4_UNICAST)
            .collect(ImmutableSortedSet.toImmutableSortedSet(Comparator.naturalOrder()));
    // Create incoming message queues for sessions that exchange EVPN info
    _evpnType3IncomingRoutes =
        getEdgeIdStream(graph, BgpPeerConfig::getEvpnAddressFamily, Type.EVPN)
            .collect(toImmutableSortedMap(Function.identity(), e -> new ConcurrentLinkedQueue<>()));
  }

  /**
   * Return a stream of BGP topology {@link EdgeId} based on BGP neighbors configured for this BGP
   * process.
   *
   * <p>Additionally filters the neighbors based on the desired address family (family must be
   * non-null for the neighbor to be considered).
   *
   * @param graph the BGP topology graph
   * @param familyExtractor function to execute on the {@link BgpPeerConfig} that returns the
   *     desired {@link AddressFamily}. If the address family is null, the peer will be omitted from
   *     edge computation
   */
  @Nonnull
  @VisibleForTesting
  Stream<EdgeId> getEdgeIdStream(
      ValueGraph<BgpPeerConfigId, BgpSessionProperties> graph,
      Function<BgpPeerConfig, AddressFamily> familyExtractor,
      Type familyType) {
    return Streams.concat(
            _process.getActiveNeighbors().entrySet().stream()
                .filter(e -> familyExtractor.apply(e.getValue()) != null)
                .map(e -> new BgpPeerConfigId(_hostname, _vrfName, e.getKey().toPrefix(), false)),
            _process.getPassiveNeighbors().entrySet().stream()
                .filter(e -> familyExtractor.apply(e.getValue()) != null)
                .map(e -> new BgpPeerConfigId(_hostname, _vrfName, e.getKey(), true)),
            _process.getInterfaceNeighbors().entrySet().stream()
                .filter(e -> familyExtractor.apply(e.getValue()) != null)
                .map(e -> new BgpPeerConfigId(_hostname, _vrfName, e.getKey())))
        .filter(graph.nodes()::contains) // avoid missing node exceptions
        .flatMap(
            id ->
                graph.incidentEdges(id).stream()
                    .filter(
                        pair -> pair.nodeV().equals(id))) // get all incoming edges for this node
        .filter(
            edge ->
                graph
                    .edgeValue(edge.nodeU(), edge.nodeV())
                    .orElseThrow(
                        () ->
                            new IllegalStateException(
                                String.format(
                                    "Bgp session without session properties for edge %s -> %s",
                                    edge.nodeU(), edge.nodeV())))
                    .getAddressFamilies()
                    .contains(familyType)) // ensure the session contains desired address family
        .map(edge -> new EdgeId(edge.nodeU(), edge.nodeV()));
  }

  @Override
  public void updateTopology(BgpTopology topology) {
    BgpTopology oldTopology = _topology;
    _topology = topology;
    initBgpQueues(_topology);
    // New sessions got established
    _unicastEdgesWentUp =
        Sets.difference(
            getEdgeIdStream(
                    topology.getGraph(),
                    BgpPeerConfig::getIpv4UnicastAddressFamily,
                    Type.IPV4_UNICAST)
                .collect(ImmutableSet.toImmutableSet()),
            getEdgeIdStream(
                    oldTopology.getGraph(),
                    BgpPeerConfig::getIpv4UnicastAddressFamily,
                    Type.IPV4_UNICAST)
                .collect(ImmutableSet.toImmutableSet()));
    _evpnEdgesWentUp =
        Sets.difference(
            getEdgeIdStream(topology.getGraph(), BgpPeerConfig::getEvpnAddressFamily, Type.EVPN)
                .collect(ImmutableSet.toImmutableSet()),
            getEdgeIdStream(oldTopology.getGraph(), BgpPeerConfig::getEvpnAddressFamily, Type.EVPN)
                .collect(ImmutableSet.toImmutableSet()));
    if (!_unicastEdgesWentUp.isEmpty()) {
      // We copy these three RIBs here instead of at the end of the previous round to avoid
      // unnecessary space/time use. However, this process is only correct if the RIBs have not
      // changed since the end of the previous round. That means deltas must (still) be empty.
      // Main RIB invariant is checked at the VirtualRouter caller, since this class does not
      // have access to the main RIB delta.
      assert _bgpv4DeltaBuilder.isEmpty();
      assert _ebgpv4DeltaBuilder.isEmpty();
      assert _bgpv4DeltaBestPathBuilder.isEmpty();
      assert _ebgpv4DeltaBestPathBuilder.isEmpty();

      if (!_exportFromBgpRib) {
        _mainRibPrev = _mainRib.getTypedRoutes();
      }
    } else {
      assert _mainRibPrev.isEmpty();
    }
    _topology = topology;
    // TODO: compute edges that went down, remove routes we received from those neighbors
  }

  @Override
  public void executeIteration(Map<String, Node> allNodes) {
    // Reinitialize delta builders
    _evpnDeltaBuilder = RibDelta.builder();

    // TODO: optimize, don't recreate the map each iteration
    NetworkConfigurations nc =
        NetworkConfigurations.of(
            allNodes.entrySet().stream()
                .collect(
                    ImmutableMap.toImmutableMap(
                        Entry::getKey, e -> e.getValue().getConfiguration())));
    if (!_evpnInitializationDelta.isEmpty()) {
      // If initialization delta has not been sent out, do so now
      sendOutEvpnType3Routes(
          new BgpDelta<>(_evpnInitializationDelta, RibDelta.empty()), nc, allNodes);
      _localType3Routes = _evpnInitializationDelta;
      _evpnInitializationDelta = RibDelta.empty();
    }

    /*
     If we have any new edges, send out our RIB state to them.
     EVPN only
    */
    sendOutRoutesToNewEdges(_evpnEdgesWentUp, allNodes, nc);

    processBgpMessages(nc, allNodes);
  }

  private void sendOutRoutesToNewEdges(
      Set<EdgeId> edgesWentUp, Map<String, Node> allNodes, NetworkConfigurations nc) {
    if (edgesWentUp.isEmpty()) {
      // Nothing to do
      return;
    }

    // Send out the state of our BGPv4 RIB to the neighbors

    // TODO: _localType3Routes is not enough
    //    Ideally we need to re-send all EVPN routes we have to new neighbors
    for (EdgeId edge : edgesWentUp) {
      sendEvpnType3RoutesToEdge(
          edge.reverse(), new BgpDelta<>(_localType3Routes, RibDelta.empty()), nc, allNodes);
    }
  }

  @Nonnull
  @Override
  public RibDelta<BgpRoute<?, ?>> getUpdatesForMainRib() {
    RibDelta<BgpRoute<?, ?>> result = _toMainRib.build();
    _toMainRib = RibDelta.builder();
    return result;
  }

  public void updateResolvableRoutes(RibDelta<AnnotatedRoute<AbstractRoute>> mainRibDelta) {
    BgpRib.MultipathRibDelta<Bgpv4Route> updateDelta = _bgpv4Rib.updateActiveRoutes(mainRibDelta);
    _bgpv4DeltaBuilder.from(updateDelta.getMultipathDelta());
    _bgpv4DeltaBestPathBuilder.from(updateDelta.getBestPathDelta());
  }

  @Override
  public void redistribute(RibDelta<AnnotatedRoute<AbstractRoute>> mainRibDelta) {
    // A redistribution policy must be defined iff exporting from BGP RIB
    assert !_exportFromBgpRib ^ _process.getRedistributionPolicy() != null;

    if (!_exportFromBgpRib) {
      // Export from main RIB; no local routes in BGP RIB (Juniper-like redistribution)
      assert _mainRibDelta.isEmpty();
      _mainRibDelta = mainRibDelta;
    } else {
      // Place redistributed routes into our RIB
      Optional<RoutingPolicy> redistributionPolicy =
          _policies.get(_process.getRedistributionPolicy());
      if (!redistributionPolicy.isPresent()) {
        LOGGER.debug(
            "Undefined BGP redistribution policy {}. Skipping redistribution",
            _process.getRedistributionPolicy());
      } else {
        mainRibDelta
            .getActions()
            .forEach(a -> redistributeRouteToBgpRib(a, redistributionPolicy.get(), REDISTRIBUTE));
        if (LOGGER.isDebugEnabled()) {
          LOGGER.debug(
              "Redistributed via redistribution policy into local BGP RIB node {}, VRF {}: {}",
              _hostname,
              _vrfName,
              _bgpv4DeltaBuilder.build());
        }
      }

      // If there is an independent network policy, run that as well
      String independentNetworkPolicyName = _process.getIndependentNetworkPolicy();
      if (independentNetworkPolicyName == null) {
        return;
      }
      Optional<RoutingPolicy> independentNetworkPolicy =
          _policies.get(independentNetworkPolicyName);
      if (!independentNetworkPolicy.isPresent()) {
        LOGGER.debug(
            "Undefined BGP independent network policy {}. Skipping redistribution",
            independentNetworkPolicyName);
      } else {
        mainRibDelta
            .getActions()
            .forEach(a -> redistributeRouteToBgpRib(a, independentNetworkPolicy.get(), NETWORK));
        if (LOGGER.isDebugEnabled()) {
          LOGGER.debug(
              "Redistributed via independent network policy into local BGP RIB node {}, VRF {}: {}",
              _hostname,
              _vrfName,
              _bgpv4DeltaBuilder.build());
        }
      }
    }
  }

  /**
   * Convert the advertised main RIB route to a BGP route and run it through the provided policy. If
   * it passes, apply the advertisement to the BGP RIB (i.e. merge or withdraw the route) and update
   * BGP delta builders.
   */
  private void redistributeRouteToBgpRib(
      RouteAdvertisement<? extends AnnotatedRoute<AbstractRoute>> routeAdv,
      RoutingPolicy policy,
      OriginMechanism originMechanism) {
    AbstractRouteDecorator route = routeAdv.getRoute();
    if (route.getAbstractRoute() instanceof BgpRoute<?, ?>) {
      // Do not place BGP routes back into BGP process.
      return;
    }
    Bgpv4Route.Builder bgpBuilder =
        BgpProtocolHelper.convertNonBgpRouteToBgpRoute(
                route,
                getRouterId(),
                route.getAbstractRoute().getNextHopIp(),
                _process.getEbgpAdminCost(),
                RoutingProtocol.BGP,
                originMechanism)
            // Prevent from funneling to main RIB
            .setNonRouting(true);

    // Hopefully, the direction should not matter here.
    boolean accept = policy.process(route, bgpBuilder, OUT, _ribExprEvaluator);
    if (!accept) {
      return;
    }
    Bgpv4Route builtBgpRoute = bgpBuilder.build();
    processMergeOrRemoveInBgpRib(builtBgpRoute, !routeAdv.isWithdrawn());
  }

  @Override
  public boolean isDirty() {
    // endOfRound gets called before the isDirty check, so delta builders should be empty.
    // We will consider the finalized deltas from the previous round instead.
    assert _ebgpv4DeltaBuilder.isEmpty();
    assert _ebgpv4DeltaBestPathBuilder.isEmpty();
    assert _bgpv4DeltaBuilder.isEmpty();
    assert _bgpv4DeltaBestPathBuilder.isEmpty();
    return
    // Message queues
    !_evpnType3IncomingRoutes.values().stream().allMatch(Queue::isEmpty)
        // Outgoing message deltas. We need to send these to neighbors.
        || !_ebgpv4DeltaPrev.isEmpty()
        || !_ebgpv4DeltaPrevBestPath.isEmpty()
        || !_bgpv4DeltaPrev.isEmpty()
        || !_bgpv4DeltaPrevBestPath.isEmpty()
        || !_mainRibDelta.isEmpty()
        // Delta builders
        || !_evpnDeltaBuilder.isEmpty()
        // Initialization state
        || !_evpnInitializationDelta.isEmpty();
  }

  /**
   * Process all incoming BGP messages: across all neighbors, across all address families.
   *
   * @param nc {@link NetworkConfigurations network configurations} wrapper
   * @param allNodes map of all network nodes
   */
  private void processBgpMessages(NetworkConfigurations nc, Map<String, Node> allNodes) {
    // Process IPv4 unicast messages
    processBgpV4UnicastMessages(_topology, nc, allNodes);

    // Process EVPN messages and send out updates
    DeltaPair<EvpnType3Route> type3Delta = processEvpnType3Messages(nc, allNodes);
    sendOutEvpnType3Routes(type3Delta._toAdvertise, nc, allNodes);
    _toMainRib.from(
        importRibDelta(_evpnRib, importRibDelta(_evpnType3Rib, type3Delta._toMerge._ebgpDelta)));
    _toMainRib.from(
        importRibDelta(_evpnRib, importRibDelta(_evpnType3Rib, type3Delta._toMerge._ibgpDelta)));
  }

  /** Initialize the EVPN RIBs based on EVPN address family config */
  @VisibleForTesting
  void initLocalEvpnRoutes(Node n) {
    // default admin costs
    int ebgpAdmin = _process.getEbgpAdminCost();

    Builder<EvpnType3Route> initializationBuilder = RibDelta.builder();
    getAllPeerConfigs(_process)
        .map(BgpPeerConfig::getEvpnAddressFamily)
        .filter(Objects::nonNull)
        .flatMap(af -> af.getL2VNIs().stream())
        .forEach(
            vniConfig -> {
              Vrf vniVrf = _c.getVrfs().get(vniConfig.getVrf());
              assert vniVrf != null; // Invariant guaranteed by proper conversion
              Layer2Vni l2Vni = vniVrf.getLayer2Vnis().get(vniConfig.getVni());
              assert l2Vni != null; // Invariant guaranteed by proper conversion
              if (l2Vni.getSourceAddress() == null) {
                return;
              }
              EvpnType3Route route =
                  initEvpnType3Route(
                      ebgpAdmin,
                      l2Vni,
                      vniConfig.getRouteTarget(),
                      vniConfig.getRouteDistinguisher(),
                      _process.getRouterId());

              if (vniVrf.getName().equals(_vrfName)) {
                // Merge into our own RIBs
                RibDelta<EvpnType3Route> d = _evpnType3Rib.mergeRouteGetDelta(route);
                _evpnDeltaBuilder.from(d);
                initializationBuilder.from(d);
              } else {
                // Merge into our sibling VRF corresponding to the VNI
                BgpRoutingProcess bgpRoutingProcess =
                    n.getVirtualRouter(vniVrf.getName())
                        .map(VirtualRouter::getBgpRoutingProcess)
                        .orElse(null);
                checkArgument(
                    bgpRoutingProcess != null,
                    "Missing bgp process for vrf %s, node %s",
                    vniVrf.getName(),
                    _hostname);
                initializationBuilder.from(
                    bgpRoutingProcess.processCrossVrfEvpnRoute(
                        new RouteAdvertisement<>(route), EvpnType3Route.class));
              }
            });
    _evpnInitializationDelta = initializationBuilder.build();
    _toMainRib.from(_evpnDeltaBuilder.build());
  }

  /**
   * Create a new {@link EvpnType3Route} based on given {@link Layer2Vni}. Assumes {@code vni} is
   * valid (e.g., has properly set source address).
   */
  @Nonnull
  @VisibleForTesting
  static EvpnType3Route initEvpnType3Route(
      int ebgpAdmin,
      Layer2Vni vni,
      ExtendedCommunity routeTarget,
      RouteDistinguisher routeDistinguisher,
      Ip routerId) {
    checkArgument(
        vni.getSourceAddress() != null,
        "Cannot construct type 3 route for invalid VNI %s",
        vni.getVni());
    // Locally all routes start as eBGP routes in our own RIB
    EvpnType3Route.Builder type3RouteBuilder = EvpnType3Route.builder();
    type3RouteBuilder.setAdmin(ebgpAdmin);
    type3RouteBuilder.setCommunities(CommunitySet.of(routeTarget));
    type3RouteBuilder.setLocalPreference(DEFAULT_LOCAL_PREFERENCE);
    // so that this route is not installed back in the main RIB of any of the VRFs
    type3RouteBuilder.setNonRouting(true);
    type3RouteBuilder.setOriginatorIp(routerId);
    type3RouteBuilder.setOriginMechanism(GENERATED);
    type3RouteBuilder.setOriginType(OriginType.EGP);
    type3RouteBuilder.setProtocol(RoutingProtocol.BGP);
    type3RouteBuilder.setRouteDistinguisher(routeDistinguisher);
    type3RouteBuilder.setVniIp(vni.getSourceAddress());
    type3RouteBuilder.setNextHop(NextHopDiscard.instance());

    return type3RouteBuilder.build();
  }

  /**
   * Process BGP messages from neighbors, merge them into our own RIBs.
   *
   * @param bgpTopology the bgp peering relationships
   */
  void processBgpV4UnicastMessages(
      BgpTopology bgpTopology, NetworkConfigurations nc, Map<String, Node> nodes) {
    // If there are any, process external advertisements. This will only be true once, the first
    // time any BGP routes are pulled.
    if (_externalAdvertisements != null) {
      _externalAdvertisements.forEach(this::processExternalBgpAdvertisement);
      _externalAdvertisements = null;
    }

    // Process updates from each neighbor
    for (EdgeId edgeId : _bgpv4Edges) {
      pullV4UnicastMessages(bgpTopology, nc, nodes, edgeId, _unicastEdgesWentUp.contains(edgeId));
    }

    initBgpAggregateRoutes();

    unstage();
  }

  /** Update main RIB. */
  private void unstage() {
    // Prepare the delta we will feed into the main RIB
    RibDelta<Bgpv4Route> bgpv4RibDelta = _bgpv4DeltaBuilder.build();
    LOGGER.trace(
        "{}: Unstaged BGP routes, current bgpv4Delta: {}", _c.getHostname(), bgpv4RibDelta);
    _toMainRib.from(bgpv4RibDelta);
  }

  /** Pull v4Unicast routes from our neighbors' deltas, merge them into our own RIBs */
  private void pullV4UnicastMessages(
      BgpTopology bgpTopology,
      NetworkConfigurations nc,
      Map<String, Node> nodes,
      EdgeId edgeId,
      boolean isNewSession) {

    // Setup helper vars
    BgpPeerConfigId remoteConfigId = edgeId.tail();
    BgpPeerConfigId ourConfigId = edgeId.head();
    // Verify that "ourConfigId" really is ours.
    assert ourConfigId.getHostname().equals(_hostname);

    BgpSessionProperties ourSessionProperties =
        getBgpSessionProperties(bgpTopology, edgeId.reverse());
    BgpPeerConfig ourBgpConfig = requireNonNull(nc.getBgpPeerConfig(edgeId.head()));
    assert ourBgpConfig.getIpv4UnicastAddressFamily() != null;
    // sessionProperties represents the incoming edge, so its tailIp is the remote peer's IP
    boolean useRibGroups =
        ourBgpConfig.getAppliedRibGroup() != null
            && !ourBgpConfig.getAppliedRibGroup().getImportRibs().isEmpty();

    Builder<AnnotatedRoute<AbstractRoute>> perNeighborDeltaForRibGroups = RibDelta.builder();

    BgpRoutingProcess remoteProcess = getNeighborBgpProcess(remoteConfigId, nodes);
    Iterator<RouteAdvertisement<Bgpv4Route>> exportedRoutes =
        remoteProcess
            .getOutgoingRoutesForEdge(edgeId, nodes, bgpTopology, nc, isNewSession)
            // Different incoming routes may be transformed to equivalent learned routes (due to
            // transformBgpRouteOnImport or the import policy). If this occurs with one withdrawn
            // route and another added route, the add should be applied second.
            .sorted(Comparator.comparing(ra -> !ra.isWithdrawn()))
            .iterator();

    // Process all routes from neighbor
    while (exportedRoutes.hasNext()) {
      // consume exported routes
      RouteAdvertisement<Bgpv4Route> remoteRouteAdvert = exportedRoutes.next();
      Bgpv4Route remoteRoute = remoteRouteAdvert.getRoute();

      Bgpv4Route.Builder transformedIncomingRouteBuilder =
          transformBgpRouteOnImport(
              remoteRoute,
              ourSessionProperties.getLocalAs(),
              ourBgpConfig
                  .getIpv4UnicastAddressFamily()
                  .getAddressFamilyCapabilities()
                  .getAllowLocalAsIn(),
              ourSessionProperties.isEbgp(),
              _process,
              ourSessionProperties.getRemoteIp(),
              ourConfigId.getPeerInterface());
      if (transformedIncomingRouteBuilder == null) {
        // Route could not be imported for core protocol reasons
        _prefixTracer.filtered(
            remoteRoute.getNetwork(),
            remoteConfigId.getHostname(),
            ourSessionProperties.getRemoteIp(),
            remoteConfigId.getVrfName(),
            null,
            IN);
        continue;
      }

      // Process route through import policy, if one exists
      String importPolicyName = ourBgpConfig.getIpv4UnicastAddressFamily().getImportPolicy();
      boolean acceptIncoming = true;
      // TODO: ensure there is always an import policy
      if (importPolicyName != null) {
        RoutingPolicy importPolicy = _policies.get(importPolicyName).orElse(null);
        if (importPolicy != null) {
          acceptIncoming =
              importPolicy.processBgpRoute(
                  remoteRoute,
                  transformedIncomingRouteBuilder,
                  ourSessionProperties,
                  IN,
                  _ribExprEvaluator);
        }
      }
      if (!acceptIncoming) {
        // Route could not be imported due to routing policy
        _prefixTracer.filtered(
            remoteRoute.getNetwork(),
            remoteConfigId.getHostname(),
            ourSessionProperties.getRemoteIp(),
            remoteConfigId.getVrfName(),
            importPolicyName,
            IN);
        continue;
      }
      Bgpv4Route transformedIncomingRoute = transformedIncomingRouteBuilder.build();

      // If new route gets leaked to other VRFs via RibGroup, this VRF should be its source VRF.
      AnnotatedRoute<AbstractRoute> annotatedTransformedRoute =
          annotateRoute(transformedIncomingRoute);

      if (remoteRouteAdvert.isWithdrawn()) {
        // Remove from target and overall RIBs and update deltas
        processRemoveInEbgpOrIbgpRib(transformedIncomingRoute, ourSessionProperties.isEbgp());
        processRemoveInBgpRib(transformedIncomingRoute);
        if (useRibGroups) {
          perNeighborDeltaForRibGroups.remove(annotatedTransformedRoute, Reason.WITHDRAW);
        }
      } else {
        // Merge into target and overall RIBs and update deltas
        processMergeInEbgpOrIbgpRib(transformedIncomingRoute, ourSessionProperties.isEbgp());
        processMergeInBgpRib(transformedIncomingRoute);
        if (useRibGroups) {
          perNeighborDeltaForRibGroups.add(annotatedTransformedRoute);
        }
        _prefixTracer.installed(
            transformedIncomingRoute.getNetwork(),
            remoteConfigId.getHostname(),
            ourSessionProperties.getRemoteIp(),
            remoteConfigId.getVrfName(),
            importPolicyName);
      }
    }
    // Apply rib groups if any
    if (useRibGroups) {
      RibGroup rg = ourBgpConfig.getAppliedRibGroup();
      rg.getImportRibs()
          .forEach(
              rib ->
                  nodes
                      .get(_hostname)
                      .getVirtualRouterOrThrow(rib.getVrfName())
                      .enqueueCrossVrfRoutes(
                          new CrossVrfEdgeId(_vrfName, rib.getRibName()),
                          perNeighborDeltaForRibGroups.build().getActions(),
                          rg.getImportPolicy()));
    }
  }

  private Stream<RouteAdvertisement<Bgpv4Route>> getOutgoingRoutesForEdge(
      EdgeId edge,
      Map<String, Node> allNodes,
      BgpTopology bgpTopology,
      NetworkConfigurations networkConfigurations,
      boolean isNewSession) {
    BgpPeerConfigId remoteConfigId = edge.head();
    BgpPeerConfigId ourConfigId = edge.tail();
    // Confirm edge directionality.
    assert ourConfigId.getHostname().equals(_hostname);
    BgpPeerConfig ourConfig = networkConfigurations.getBgpPeerConfig(edge.tail());
    assert ourConfig != null;
    BgpPeerConfig remoteConfig = networkConfigurations.getBgpPeerConfig(edge.head());
    assert remoteConfig != null;

    BgpSessionProperties ourSession = BgpRoutingProcess.getBgpSessionProperties(bgpTopology, edge);

    // If exporting from main RIB, queue mainRib updates that were not introduced by BGP process
    // (i.e., IGP routes). Also, do not double-export main RIB routes: filter out bgp routes.
    Stream<RouteAdvertisement<Bgpv4Route>> mainRibExports = Stream.of();
    if (!_exportFromBgpRib) {
      mainRibExports =
          Stream.concat(
                  isNewSession
                      // Start with the entire main RIB if this session is new.
                      ? _mainRibPrev.stream().map(RouteAdvertisement::adding)
                      : Stream.of(),
                  _mainRibDelta.getActions())
              .filter(adv -> !(adv.getRoute().getRoute() instanceof BgpRoute))
              .map(
                  adv -> {
                    _prefixTracer.originated(adv.getRoute().getNetwork());
                    Bgpv4Route bgpRoute =
                        exportNonBgpRouteToBgp(
                            adv.getRoute(), remoteConfigId, ourConfig, ourSession);
                    if (bgpRoute == null) {
                      return null;
                    }
                    return RouteAdvertisement.<Bgpv4Route>builder()
                        .setReason(adv.getReason())
                        .setRoute(bgpRoute)
                        .build();
                  })
              .filter(Objects::nonNull);
    }

    // Needs to retain annotations since export policy will be run on routes from resulting delta.
    Builder<AnnotatedRoute<Bgpv4Route>> bgpRibExports = RibDelta.builder();
    /*
     * By default only best-path routes from the BGP RIB that are **also installed in the main RIB**
     * will be advertised to our neighbors.
     *
     * However, there are additional knobs that control re-advertisement behavior:
     *
     * 1. Advertise external: advertise best-path eBGP routes to iBGP peers regardless of whether
     *    they are global BGP best-paths.
     * 2. Advertise inactive: advertise best-path BGP routes to neighboring peers even if
     *    they are not active in the main RIB.
     */
    Set<Bgpv4Route> bgpv4Prev;
    RibDelta<Bgpv4Route> bgpv4DeltaPrev;
    Set<Bgpv4Route> ebgpv4Prev;
    RibDelta<Bgpv4Route> ebgpv4DeltaPrev;
    if (ourSession.getAdditionalPaths()) {
      /*
      TODO: https://github.com/batfish/batfish/issues/704
         Add path is broken for all intents and purposes.
         Need support for additional-paths based on https://tools.ietf.org/html/rfc7911
         AND the combination of vendor-specific knobs, none of which are currently supported.
      */
      // TODO: sending withdrawals with add-path is likely unsafe.
      bgpv4Prev = _bgpv4Prev;
      bgpv4DeltaPrev = _bgpv4DeltaPrev;
      ebgpv4Prev = _ebgpv4Prev;
      ebgpv4DeltaPrev = _ebgpv4DeltaPrev;
    } else {
      bgpv4Prev = _bgpv4PrevBestPath;
      bgpv4DeltaPrev = _bgpv4DeltaPrevBestPath;
      ebgpv4Prev = _ebgpv4PrevBestPath;
      ebgpv4DeltaPrev = _ebgpv4DeltaPrevBestPath;
    }
    if (ourSession.getAdvertiseExternal()) {
      if (isNewSession) {
        bgpRibExports.from(
            ebgpv4Prev.stream().map(this::annotateRoute).map(RouteAdvertisement::new));
      }
      importDeltaToBuilder(bgpRibExports, ebgpv4DeltaPrev, _vrfName);
    }

    if (ourSession.getAdvertiseInactive()) {
      if (isNewSession) {
        bgpRibExports.from(
            bgpv4Prev.stream().map(this::annotateRoute).map(RouteAdvertisement::new));
      }
      importDeltaToBuilder(bgpRibExports, bgpv4DeltaPrev, _vrfName);

    } else {
      // Default behavior
      bgpRibExports.from(
          Stream.concat(
                  isNewSession
                      ? bgpv4Prev.stream().map(this::annotateRoute).map(RouteAdvertisement::new)
                      : Stream.of(),
                  bgpv4DeltaPrev
                      .getActions()
                      .map(
                          r ->
                              RouteAdvertisement.<AnnotatedRoute<Bgpv4Route>>builder()
                                  .setReason(r.getReason())
                                  .setRoute(annotateRoute(r.getRoute()))
                                  .build()))
              .filter(
                  r ->
                      // Withdrawals
                      r.isWithdrawn()
                          // Received from 0.0.0.0 indicates local origination
                          || (_exportFromBgpRib
                              && Ip.ZERO.equals(r.getRoute().getRoute().getReceivedFromIp()))
                          // RIB-failure routes included
                          || isReflectable(r.getRoute(), ourSession, ourConfig)
                          // RIB-failure routes excluded
                          || _mainRib.containsRoute(r.getRoute())));
    }

    /*
     * Export neighbor-specific generated routes.
     * These skip peer export policy, so do not merge them into bgpRoutesToExport
     */
    BgpRoutingProcess remoteBgpRoutingProcess = getNeighborBgpProcess(remoteConfigId, allNodes);
    Stream<RouteAdvertisement<Bgpv4Route>> neighborGeneratedRoutes =
        ourConfig.getGeneratedRoutes().stream()
            .map(
                r -> {
                  // Activate route and convert to BGP if activated
                  Bgpv4Route bgpv4Route =
                      processNeighborSpecificGeneratedRoute(r, ourSession.getLocalIp());
                  if (bgpv4Route == null) {
                    // Route was not activated
                    return Optional.<Bgpv4Route>empty();
                  }
                  // Run pre-export transform, export policy, & post-export transform
                  return transformBgpRouteOnExport(
                      bgpv4Route,
                      ourConfigId,
                      remoteConfigId,
                      ourConfig,
                      remoteConfig,
                      remoteBgpRoutingProcess,
                      ourSession,
                      Type.IPV4_UNICAST);
                })
            .filter(Optional::isPresent)
            .map(Optional::get)
            .map(RouteAdvertisement::new);

    RibDelta<AnnotatedRoute<Bgpv4Route>> bgpRoutesToExport = bgpRibExports.build();

    // Compute a set of advertisements that can be queued on remote VR
    Stream<RouteAdvertisement<Bgpv4Route>> advertisementStream =
        Stream.concat(
            bgpRoutesToExport
                .getActions()
                .map(
                    adv -> {
                      Optional<Bgpv4Route> transformedRoute =
                          transformBgpRouteOnExport(
                              adv.getRoute().getRoute(),
                              ourConfigId,
                              remoteConfigId,
                              ourConfig,
                              remoteConfig,
                              remoteBgpRoutingProcess,
                              ourSession,
                              Type.IPV4_UNICAST);
                      // REPLACE does not make sense across routers, update with WITHDRAW
                      return transformedRoute
                          .map(
                              bgpv4Route ->
                                  RouteAdvertisement.<Bgpv4Route>builder()
                                      .setReason(
                                          adv.getReason() == Reason.REPLACE
                                              ? Reason.WITHDRAW
                                              : adv.getReason())
                                      .setRoute(bgpv4Route)
                                      .build())
                          .orElse(null);
                    })
                .filter(Objects::nonNull)
                .distinct(),
            mainRibExports);

    return Stream.concat(advertisementStream, neighborGeneratedRoutes);
  }

  private static boolean isReflectable(
      AnnotatedRoute<Bgpv4Route> route, BgpSessionProperties session, BgpPeerConfig ourConfig) {
    switch (session.getSessionType()) {
      case IBGP:
      case IBGP_UNNUMBERED:
        break;
      default:
        return false;
    }
    return route.getRoute().getProtocol().equals(RoutingProtocol.IBGP)
        && ourConfig.getAddressFamily(Type.IPV4_UNICAST).getRouteReflectorClient();
  }

  /**
   * Check whether given {@link GeneratedRoute} should be sent to a BGP neighbor. This checks
   * activation conditions for the generated route, and converts it to a {@link Bgpv4Route}. No
   * export policy computation is performed.
   *
   * @param generatedRoute route to process
   * @return a new {@link Bgpv4Route} if the {@code generatedRoute} was activated.
   */
  @Nullable
  private Bgpv4Route processNeighborSpecificGeneratedRoute(
      @Nonnull GeneratedRoute generatedRoute, Ip nextHopIp) {
    String policyName = generatedRoute.getGenerationPolicy();
    RoutingPolicy policy = policyName != null ? _policies.get(policyName).orElse(null) : null;
    @Nullable
    RoutingPolicy attrPolicy =
        generatedRoute.getAttributePolicy() != null
            ? _policies.get(generatedRoute.getAttributePolicy()).orElse(null)
            : null;
    // This kind of generation policy should not need access to the main rib
    GeneratedRoute.Builder builder =
        GeneratedRouteHelper.activateGeneratedRoute(
            generatedRoute, policy, _mainRib.getTypedRoutes(), null);
    return builder != null
        ? BgpProtocolHelper.convertGeneratedRouteToBgp(
            builder.build(), attrPolicy, _process.getRouterId(), nextHopIp, false)
        : null;
  }

  /**
   * This function creates BGP routes from generated routes that go into the BGP RIB, but cannot be
   * imported into the main RIB. The purpose of these routes is to prevent the local router from
   * accepting advertisements less desirable than the locally generated ones for a given network.
   *
   * <p>This function is deprecated, and only used by Cisco-style devices that have not been ported
   * over to initialize BGP aggregates with contributors from the BGP RIB. Once all Cisco-style
   * vendors have been ported, this function should be removed.
   */
  void initBgpAggregateRoutesLegacy(Collection<AbstractRoute> generatedRoutes) {
    if (_exportFromBgpRib) {
      // Vendors for which this is true should not be using this legacy aggregates implementation.
      return;
    }
    // TODO: get rid of ConfigurationFormat switching. Source of known bugs.
    // first import aggregates
    switch (_c.getConfigurationFormat()) {
      case FLAT_JUNIPER:
      case JUNIPER:
      case JUNIPER_SWITCH:
        return;
        // $CASES-OMITTED$
      default:
        break;
    }
    for (AbstractRoute grAbstract : generatedRoutes) {
      GeneratedRoute gr = (GeneratedRoute) grAbstract;

      Bgpv4Route br =
          BgpProtocolHelper.convertGeneratedRouteToBgp(
              gr,
              Optional.ofNullable(gr.getAttributePolicy()).flatMap(_policies::get).orElse(null),
              _process.getRouterId(),
              Ip.AUTO,
              // Prevent route from being merged into the main RIB by marking it non-routing
              true);
      /* TODO: tests for this */
      RibDelta<Bgpv4Route> d1 = processMergeInBgpRib(br);
      if (!d1.isEmpty()) {
        _bgpAggDeps.addRouteDependency(br, gr);
      }
    }
  }

  /**
   * Create BGP RIB entries from rules for generating aggregates, and update suppressed status of
   * routes contributing to said aggregates.
   *
   * @return the {@link RibDelta} of {@link Bgpv4Route}s that was applied to the BGP RIB and its
   *     delta builder.
   */
  @Nonnull
  RibDelta<Bgpv4Route> initBgpAggregateRoutes() {
    /*
     * Implementation overview:
     * 1. Start building a rib delta that has withdrawals for all old aggregates
     * 2. For each non-aggregate route in the BGP RIB, record the most specific aggregate to which
     *    it might contribute.
     * 3. Going from most specific to least specific aggregate:
     *    - Attempt to activate the aggregate via its recorded potential contributors
     *    - Find the most specific aggregate to which this aggregate might contribute if it were
     *      activated.
     *    - If such a more general aggregate exists:
     *      - Regardless of whether this aggregate is activated, record all potential contributors
     *        to this aggregate as potential contributors the the more general aggregate.
     *      - If this aggregate is activated, record it as a potential contributor to the more
     *        general aggregate.
     * 4. Add all activated aggregates to the rib delta we were building. At the fixed point, all
     *    withdrawals should be canceled out by these adds.
     * 5. Apply the finalized rib delta to the BGP RIB and to the BGP RIB delta builder.
     */
    if (_process.getAggregates().isEmpty()) {
      // Nothing to do, so don't bother with unnecessary prep.
      return RibDelta.empty();
    }
    Set<Bgpv4Route> currentRoutes = _bgpv4Rib.getTypedRoutes();
    RibDelta.Builder<Bgpv4Route> aggDeltaBuilder = RibDelta.builder();
    // Withdraw old aggregates. Withdrawals may be canceled out by activated aggregates below.
    currentRoutes.stream()
        .filter(r -> r.getProtocol() == RoutingProtocol.AGGREGATE)
        .forEach(prevAggregate -> aggDeltaBuilder.remove(prevAggregate, Reason.WITHDRAW));
    Multimap<Prefix, Bgpv4Route> potentialContributorsByAggregatePrefix =
        MultimapBuilder.hashKeys(_process.getAggregates().size()).linkedListValues().build();
    // Map each non-aggregate potential contributor to its most specific containing aggregate if it
    // exists.
    currentRoutes.stream()
        .filter(r -> r.getProtocol() != RoutingProtocol.AGGREGATE)
        .forEach(
            potentialContributor ->
                mapPotentialContributorToMostSpecificAggregate(
                    potentialContributorsByAggregatePrefix, potentialContributor));
    int admin = _process.getLocalAdminCost();
    // Traverse aggregates from most specific to least specific
    _aggregates.traverseEntries(
        (aggNet, aggregatesAtNode) -> {
          if (aggregatesAtNode.isEmpty()) {
            // intermediate node of the PrefixTrieMultimap
            return;
          }
          BgpAggregate aggregate = Iterables.getOnlyElement(aggregatesAtNode);
          Collection<Bgpv4Route> potentialContributors =
              potentialContributorsByAggregatePrefix.get(aggNet);
          Bgpv4Route activatedAggregate = null;
          for (Bgpv4Route potentialContributor : potentialContributors) {
            // TODO: apply suppressionPolicy
            // TODO: apply and merge transformations of generationPolicy
            RoutingPolicy generationPolicy =
                Optional.ofNullable(aggregate.getGenerationPolicy())
                    .map(_c.getRoutingPolicies()::get)
                    .orElse(null);
            if (generationPolicy == null
                || generationPolicy.processReadOnly(potentialContributor)) {
              // When merging is supported, the aggregate should be updated by each contributor
              // instead of just the first one.
              activatedAggregate =
                  toBgpv4Route(
                      aggregate,
                      Optional.ofNullable(aggregate.getAttributePolicy())
                          .map(_c.getRoutingPolicies()::get)
                          .orElse(null),
                      admin,
                      _process.getRouterId());
              aggDeltaBuilder.add(activatedAggregate);
              break;
            }
          }
          BgpAggregate moreGeneralAggregate = getMostSpecificAggregate(aggNet).orElse(null);
          if (moreGeneralAggregate != null) {
            Prefix moreGeneralAggregatePrefix = moreGeneralAggregate.getNetwork();
            // funnel all potential contributors down to more general aggregate
            potentialContributorsByAggregatePrefix.putAll(
                moreGeneralAggregatePrefix, potentialContributors);
            if (activatedAggregate != null) {
              // funnel this activated aggregate down to more general aggregate
              potentialContributorsByAggregatePrefix.put(
                  moreGeneralAggregatePrefix, activatedAggregate);
            }
          }
        });
    RibDelta<Bgpv4Route> aggDelta = aggDeltaBuilder.build();
    aggDelta
        .getActions()
        .forEach(action -> processMergeOrRemoveInBgpRib(action.getRoute(), !action.isWithdrawn()));
    return aggDelta;
  }

  private void mapPotentialContributorToMostSpecificAggregate(
      Multimap<Prefix, Bgpv4Route> potentialContributorsByAggregatePrefix,
      Bgpv4Route potentialContributor) {
    Optional<BgpAggregate> maybeAggregate =
        getMostSpecificAggregate(potentialContributor.getNetwork());
    if (!maybeAggregate.isPresent()) {
      return;
    }
    potentialContributorsByAggregatePrefix.put(
        maybeAggregate.get().getNetwork(), potentialContributor);
  }

  private @Nonnull Optional<BgpAggregate> getMostSpecificAggregate(
      Prefix potentialContributingPrefix) {
    if (potentialContributingPrefix.equals(Prefix.ZERO)) {
      return Optional.empty();
    }
    // There can only be zero or one aggregates at a node in the PrefixTrieMultimap because it is
    // populated from a Map<Prefix, BgpAggregate>.
    return _aggregates
        .longestPrefixMatch(
            potentialContributingPrefix.getStartIp(),
            potentialContributingPrefix.getPrefixLength() - 1)
        .stream()
        .findFirst();
  }

  private <R extends AbstractRoute> AnnotatedRoute<R> annotateRoute(@Nonnull R route) {
    return new AnnotatedRoute<>(route, _vrfName);
  }

  /** Process incoming EVPN type 3 messages, across all neighbors */
  private DeltaPair<EvpnType3Route> processEvpnType3Messages(
      NetworkConfigurations nc, Map<String, Node> allNodes) {
    DeltaPair<EvpnType3Route> deltaPair = DeltaPair.empty();
    for (Entry<EdgeId, Queue<RouteAdvertisement<EvpnType3Route>>> entry :
        _evpnType3IncomingRoutes.entrySet()) {
      EdgeId edge = entry.getKey();
      Queue<RouteAdvertisement<EvpnType3Route>> queue = entry.getValue();
      deltaPair =
          deltaPair.union(
              processEvpnMessagesFromNeighbor(edge, queue, nc, allNodes, EvpnType3Route.class));
    }
    return deltaPair;
  }

  /** Process all incoming EVPN messages for a given session, identified by {@code edge} */
  private <B extends EvpnRoute.Builder<B, R>, R extends EvpnRoute<B, R>>
      DeltaPair<R> processEvpnMessagesFromNeighbor(
          EdgeId edge,
          Queue<RouteAdvertisement<R>> queue,
          NetworkConfigurations nc,
          Map<String, Node> allNodes,
          Class<R> clazz) {
    BgpPeerConfigId ourConfigId = edge.head();
    BgpPeerConfig ourBgpConfig = nc.getBgpPeerConfig(ourConfigId);
    assert ourBgpConfig != null; // because the edge exists
    assert ourBgpConfig.getEvpnAddressFamily() != null;
    // Reverse the edge so that these are "our" session properties - tail is this node.
    BgpSessionProperties ourSessionProperties = getSessionProperties(_topology, edge.reverse());
    EvpnRib<R> targetRib =
        getRib(clazz, ourSessionProperties.isEbgp() ? RibType.EBGP : RibType.IBGP);
    RibDelta.Builder<R> toAdvertise = RibDelta.builder();
    RibDelta.Builder<R> toMerge = RibDelta.builder();
    while (!queue.isEmpty()) {
      RouteAdvertisement<R> routeAdvertisement = queue.remove();
      R route = routeAdvertisement.getRoute();
      B transformedBuilder =
          transformBgpRouteOnImport(
              route,
              ourSessionProperties.getLocalAs(),
              ourBgpConfig
                  .getEvpnAddressFamily()
                  .getAddressFamilyCapabilities()
                  .getAllowLocalAsIn(),
              ourSessionProperties.isEbgp(),
              _process,
              ourSessionProperties.getRemoteIp(),
              ourConfigId.getPeerInterface());
      if (transformedBuilder == null) {
        continue;
      }

      // Process route through import policy, if one exists
      String importPolicyName = ourBgpConfig.getEvpnAddressFamily().getImportPolicy();
      boolean acceptIncoming = true;
      if (importPolicyName != null) {
        RoutingPolicy importPolicy = _policies.get(importPolicyName).orElse(null);
        if (importPolicy != null) {
          acceptIncoming =
              importPolicy.processBgpRoute(
                  route, transformedBuilder, ourSessionProperties, IN, _ribExprEvaluator);
        }
      }
      if (!acceptIncoming) {
        continue;
      }
      if (clazz.equals(EvpnType3Route.class)) {
        // Type 3 routes are special: they don't go into main RIB, they only update L2 VNI's flood
        // list
        transformedBuilder.setNonRouting(true);
      }
      R transformedRoute = transformedBuilder.build();
      Set<ExtendedCommunity> routeTargets = transformedRoute.getRouteTargets();
      if (routeTargets.isEmpty()) {
        // Skip if the route target is unrecognized
        continue;
      }
      // TODO:
      //  handle multiple route targets pointing to different VRFs (should merge into multiple VRFs)
      ExtendedCommunity routeTarget = routeTargets.iterator().next();
      Optional<String> targetVrf =
          _rtVrfMapping.entrySet().stream()
              .filter(e -> Pattern.compile(e.getKey()).matcher(routeTarget.matchString()).matches())
              .map(Entry::getValue)
              .findFirst();
      if (targetVrf.isPresent()) {
        if (_vrfName.equals(targetVrf.get())) {
          // Merge into our own RIBs, and put into re-advertisement delta
          RibDelta<R> d = targetRib.mergeRouteGetDelta(transformedRoute);
          toAdvertise.from(d);
          toMerge.from(d);
        } else {
          // Merge into other VRF's RIB and put into re-advertisement delta
          toAdvertise.from(
              getVrfProcess(targetVrf.get(), allNodes)
                  .processCrossVrfEvpnRoute(
                      routeAdvertisement.toBuilder().setRoute(transformedRoute).build(), clazz));
        }
      } else {
        // Simply propagate to neighbors, nothing to do locally
        toAdvertise.from(routeAdvertisement);
      }
    }

    BgpDelta<R> advertiseDelta =
        ourSessionProperties.isEbgp()
            ? new BgpDelta<>(toAdvertise.build(), RibDelta.empty())
            : new BgpDelta<>(RibDelta.empty(), toAdvertise.build());
    BgpDelta<R> mergeDelta =
        ourSessionProperties.isEbgp()
            ? new BgpDelta<>(toMerge.build(), RibDelta.empty())
            : new BgpDelta<>(RibDelta.empty(), toMerge.build());
    return new DeltaPair<>(advertiseDelta, mergeDelta);
  }

  /** Send out EVPN type 3 routes to our neighbors */
  private void sendOutEvpnType3Routes(
      BgpDelta<EvpnType3Route> evpnDelta, NetworkConfigurations nc, Map<String, Node> allNodes) {
    _evpnType3IncomingRoutes
        .keySet()
        .forEach(edge -> sendEvpnType3RoutesToEdge(edge.reverse(), evpnDelta, nc, allNodes));
  }

  private void sendEvpnType3RoutesToEdge(
      EdgeId outgoingEdge,
      BgpDelta<EvpnType3Route> delta,
      NetworkConfigurations nc,
      Map<String, Node> allNodes) {
    // Confirm edge is outgoing.
    assert _hostname.equals(outgoingEdge.tail().getHostname());
    BgpSessionProperties ourSession = getSessionProperties(_topology, outgoingEdge);
    Stream<RouteAdvertisement<EvpnType3Route>> evpnAdvertisements =
        getEvpnTransformedRouteStream(outgoingEdge, delta, nc, allNodes, ourSession);
    getNeighborBgpProcess(outgoingEdge.head(), allNodes)
        .enqueueEvpnType3Routes(outgoingEdge, evpnAdvertisements);
  }

  @Nonnull
  private <B extends EvpnRoute.Builder<B, R>, R extends EvpnRoute<B, R>>
      Stream<RouteAdvertisement<R>> getEvpnTransformedRouteStream(
          EdgeId outgoingEdge,
          BgpDelta<R> evpnDelta,
          NetworkConfigurations nc,
          Map<String, Node> allNodes,
          BgpSessionProperties ourSession) {
    BgpPeerConfigId ourConfigId = outgoingEdge.tail();
    BgpPeerConfigId remoteConfigId = outgoingEdge.head();
    // Verify ourConfig is for this process.
    assert _hostname.equals(ourConfigId.getHostname());

    BgpPeerConfig ourConfig = nc.getBgpPeerConfig(ourConfigId);
    BgpPeerConfig remoteConfig = nc.getBgpPeerConfig(remoteConfigId);
    assert ourConfig != null; // Invariant of the edge existing
    assert remoteConfig != null; // Invariant of the edge existing
    BgpRoutingProcess remoteBgpRoutingProcess = getNeighborBgpProcess(remoteConfigId, allNodes);
    return Stream.concat(evpnDelta._ebgpDelta.getActions(), evpnDelta._ibgpDelta.getActions())
        .map(
            // TODO: take into account address-family session settings, such as add-path or
            //   advertise-inactive
            adv ->
                transformBgpRouteOnExport(
                        // clear non-routing flag if set before sending it out
                        adv.getRoute().toBuilder().setNonRouting(false).build(),
                        ourConfigId,
                        remoteConfigId,
                        ourConfig,
                        remoteConfig,
                        remoteBgpRoutingProcess,
                        ourSession,
                        Type.EVPN)
                    .map(
                        r ->
                            RouteAdvertisement.<R>builder()
                                .setReason(
                                    adv.getReason() == Reason.REPLACE
                                        ? Reason.WITHDRAW
                                        : adv.getReason())
                                .setRoute(r)
                                .build()))
        .filter(Optional::isPresent)
        .map(Optional::get);
  }

  /**
   * Given a {@link BgpRoute}, run it through the BGP outbound transformations and export routing
   * policy.
   *
   * @param exportCandidate a route to try and export
   * @param ourConfig {@link BgpPeerConfig} that sends the route
   * @param remoteConfig {@link BgpPeerConfig} that will be receiving the route
   * @param remoteBgpRoutingProcess {@link BgpRoutingProcess} that will be recieving the route
   * @param ourSessionProperties {@link BgpSessionProperties} representing the <em>outgoing</em>
   *     edge: i.e. the edge from {@code ourConfig} to {@code remoteConfig}
   * @param afType {@link AddressFamily.Type} for which the transformation should occur
   * @return The transformed route as a {@link Bgpv4Route}, or {@code null} if the route should not
   *     be exported.
   */
  <B extends BgpRoute.Builder<B, R>, R extends BgpRoute<B, R>>
      Optional<R> transformBgpRouteOnExport(
          BgpRoute<B, R> exportCandidate,
          BgpPeerConfigId ourConfigId,
          BgpPeerConfigId remoteConfigId,
          BgpPeerConfig ourConfig,
          BgpPeerConfig remoteConfig,
          BgpRoutingProcess remoteBgpRoutingProcess,
          BgpSessionProperties ourSessionProperties,
          AddressFamily.Type afType) {
    // Verify that our means this routing process
    assert ourConfigId.getHostname().equals(_hostname);

    // Do some sanity checking first -- AF and policies should exist
    AddressFamily addressFamily = ourConfig.getAddressFamily(afType);
    checkArgument(
        addressFamily != null,
        "Missing address family %s for BGP peer %s",
        addressFamily,
        ourConfigId);
    String exportPolicyName = addressFamily.getExportPolicy();
    assert exportPolicyName != null; // Conversion guarantee
    RoutingPolicy exportPolicy = _policies.get(exportPolicyName).orElse(null);
    assert exportPolicy != null; // Conversion guarantee

    B transformedOutgoingRouteBuilder =
        BgpProtocolHelper.transformBgpRoutePreExport(
            ourConfig,
            remoteConfig,
            ourSessionProperties,
            _process,
            remoteBgpRoutingProcess._process,
            exportCandidate,
            addressFamily.getType());

    if (transformedOutgoingRouteBuilder == null) {
      // This route could not be exported for core bgp protocol reasons
      return Optional.empty();
    }

    // Process transformed outgoing route by the export policy
    boolean shouldExport =
        exportPolicy.processBgpRoute(
            exportCandidate,
            transformedOutgoingRouteBuilder,
            ourSessionProperties,
            Direction.OUT,
            _ribExprEvaluator);

    if (!shouldExport) {
      // This route could not be exported due to export policy
      _prefixTracer.filtered(
          exportCandidate.getNetwork(),
          remoteConfigId.getHostname(),
          ourSessionProperties.getRemoteIp(),
          remoteConfigId.getVrfName(),
          exportPolicyName,
          Direction.OUT);
      return Optional.empty();
    }
    // Apply final post-policy transformations before sending advertisement to neighbor
    BgpProtocolHelper.transformBgpRoutePostExport(
        transformedOutgoingRouteBuilder,
        ourSessionProperties,
        addressFamily,
        exportCandidate.getNextHopIp());
    // Successfully exported route
    R transformedOutgoingRoute = transformedOutgoingRouteBuilder.build();

    _prefixTracer.sentTo(
        transformedOutgoingRoute.getNetwork(),
        remoteConfigId.getHostname(),
        ourSessionProperties.getRemoteIp(),
        remoteConfigId.getVrfName(),
        exportPolicyName);

    return Optional.of(transformedOutgoingRoute);
  }

  /**
   * Given an {@link AbstractRoute}, run it through the BGP outbound transformations and export
   * routing policy.
   *
   * @param exportCandidate a route to try and export
   * @param ourConfig {@link BgpPeerConfig} that sends the route
   * @param ourSessionProperties {@link BgpSessionProperties} representing the <em>outgoing</em>
   *     edge: i.e. the edge from {@code ourConfig} to {@code remoteConfig}
   * @return The transformed route as a {@link Bgpv4Route}, or {@code null} if the route should not
   *     be exported.
   */
  /*
   * Notes:
   *   our == edge.tail == sender == local          (this BgpRoutingProcess)
   *   remote == edge.head == receiver              (the neighbor's BgpRoutingProcess)
   */
  @Nullable
  Bgpv4Route exportNonBgpRouteToBgp(
      @Nonnull AnnotatedRoute<AbstractRoute> exportCandidate,
      @Nonnull BgpPeerConfigId remoteConfigId,
      @Nonnull BgpPeerConfig ourConfig,
      @Nonnull BgpSessionProperties ourSessionProperties) {
    @Nullable AddressFamily v4Family = ourConfig.getIpv4UnicastAddressFamily();
    if (v4Family == null) {
      return null;
    }
    String exportPolicyName = v4Family.getExportPolicy();
    assert exportPolicyName != null; // Conversion guarantee
    RoutingPolicy exportPolicy = _policies.getOrThrow(exportPolicyName);
    RoutingProtocol protocol =
        ourSessionProperties.isEbgp() ? RoutingProtocol.BGP : RoutingProtocol.IBGP;

    Bgpv4Route.Builder transformedOutgoingRouteBuilder =
        exportCandidate.getRoute() instanceof GeneratedRoute
            ? BgpProtocolHelper.convertGeneratedRouteToBgp(
                (GeneratedRoute) exportCandidate.getRoute(),
                Optional.ofNullable(
                        ((GeneratedRoute) exportCandidate.getRoute()).getAttributePolicy())
                    .flatMap(_policies::get)
                    .orElse(null),
                _process.getRouterId(),
                ourSessionProperties.getLocalIp(),
                false)
                .toBuilder()
            : BgpProtocolHelper.convertNonBgpRouteToBgpRoute(
                exportCandidate,
                getRouterId(),
                ourSessionProperties.getLocalIp(),
                _process.getAdminCost(protocol),
                protocol,
                REDISTRIBUTE);

    // Process transformed outgoing route by the export policy
    boolean shouldExport =
        exportPolicy.processBgpRoute(
            exportCandidate,
            transformedOutgoingRouteBuilder,
            ourSessionProperties,
            Direction.OUT,
            _ribExprEvaluator);

    if (!shouldExport) {
      // This route could not be exported due to export policy
      _prefixTracer.filtered(
          exportCandidate.getNetwork(),
          remoteConfigId.getHostname(),
          ourSessionProperties.getRemoteIp(),
          remoteConfigId.getVrfName(),
          exportPolicyName,
          Direction.OUT);
      return null;
    }

    // Apply final post-policy transformations before sending advertisement to neighbor
    BgpProtocolHelper.transformBgpRoutePostExport(
        transformedOutgoingRouteBuilder,
        ourSessionProperties,
        v4Family,
        Route.UNSET_ROUTE_NEXT_HOP_IP);

    // Successfully exported route
    Bgpv4Route transformedOutgoingRoute = transformedOutgoingRouteBuilder.build();
    _prefixTracer.sentTo(
        transformedOutgoingRoute.getNetwork(),
        remoteConfigId.getHostname(),
        ourSessionProperties.getRemoteIp(),
        remoteConfigId.getVrfName(),
        exportPolicyName);

    return transformedOutgoingRoute;
  }

  @VisibleForTesting
  void processExternalBgpAdvertisement(BgpAdvertisement advert) {
    Ip srcIp = advert.getSrcIp();
    // TODO: support passive and unnumbered bgp connections
    BgpActivePeerConfig neighbor = _process.getActiveNeighbors().get(srcIp);
    assert neighbor != null; // invariant of being processed

    // Build a route based on the type of this advertisement
    BgpAdvertisementType type = advert.getType();
    boolean ebgp = type.isEbgp();
    RoutingProtocol targetProtocol = ebgp ? RoutingProtocol.BGP : RoutingProtocol.IBGP;

    // Route advertisement does not contain its admin distance.
    int admin = _process.getAdminCost(targetProtocol);

    if (type.isReceived()) {
      // Since the route is received, it is from a post-import-chain view of the route.
      // Copy its attributes directly where possible then directly import it into the RIB.
      Bgpv4Route route =
          Bgpv4Route.testBuilder()
              .setAdmin(admin)
              .setAsPath(advert.getAsPath())
              .setClusterList(advert.getClusterList())
              .setCommunities(advert.getCommunities())
              .setLocalPreference(advert.getLocalPreference())
              .setMetric(advert.getMed())
              .setNetwork(advert.getNetwork())
              .setNextHopIp(advert.getNextHopIp())
              .setOriginatorIp(advert.getOriginatorIp())
              .setOriginType(advert.getOriginType())
              .setProtocol(targetProtocol)
              .setReceivedFromIp(advert.getSrcIp())
              // TODO: support external route reflector clients
              .setReceivedFromRouteReflectorClient(false)
              .setSrcProtocol(advert.getSrcProtocol())
              // TODO: possibly support setting tag
              .setWeight(advert.getWeight())
              .build();
      processMergeInEbgpOrIbgpRib(route, ebgp);
      processMergeInBgpRib(route);
    } else {
      // Since the route was logged after sending, it is from a pre-import-chain view of the route.
      // Override some attributes with local ones, then send it through the import policy.
      long localPreference;
      if (ebgp) {
        localPreference = DEFAULT_LOCAL_PREFERENCE;
      } else {
        localPreference = advert.getLocalPreference();
      }
      Bgpv4Route transformedOutgoingRoute =
          Bgpv4Route.testBuilder()
              .setAsPath(advert.getAsPath())
              .setClusterList(advert.getClusterList())
              .setCommunities(advert.getCommunities())
              .setLocalPreference(localPreference)
              .setMetric(advert.getMed())
              .setNetwork(advert.getNetwork())
              .setNextHopIp(advert.getNextHopIp())
              .setOriginatorIp(advert.getOriginatorIp())
              .setOriginType(advert.getOriginType())
              .setProtocol(targetProtocol)
              .setReceivedFromIp(advert.getSrcIp())
              // TODO .setReceivedFromRouteReflectorClient(...)
              .setSrcProtocol(advert.getSrcProtocol())
              .build();
      Bgpv4Route.Builder transformedIncomingRouteBuilder =
          Bgpv4Route.testBuilder()
              .setAdmin(admin)
              .setAsPath(transformedOutgoingRoute.getAsPath())
              .setClusterList(transformedOutgoingRoute.getClusterList())
              .setCommunities(transformedOutgoingRoute.getCommunities())
              .setLocalPreference(transformedOutgoingRoute.getLocalPreference())
              .setMetric(transformedOutgoingRoute.getMetric())
              .setNetwork(transformedOutgoingRoute.getNetwork())
              .setNextHopIp(transformedOutgoingRoute.getNextHopIp())
              .setOriginType(transformedOutgoingRoute.getOriginType())
              .setOriginatorIp(transformedOutgoingRoute.getOriginatorIp())
              .setReceivedFromIp(transformedOutgoingRoute.getReceivedFromIp())
              .setReceivedFromRouteReflectorClient(
                  transformedOutgoingRoute.getReceivedFromRouteReflectorClient())
              .setProtocol(targetProtocol)
              .setSrcProtocol(targetProtocol);
      String ipv4ImportPolicyName = neighbor.getIpv4UnicastAddressFamily().getImportPolicy();
      // TODO: ensure there is always an import policy

      /*
       * CREATE INCOMING ROUTE
       */
      boolean acceptIncoming = true;
      if (ipv4ImportPolicyName != null) {
        RoutingPolicy ipv4ImportPolicy = _policies.get(ipv4ImportPolicyName).orElse(null);
        if (ipv4ImportPolicy != null) {
          acceptIncoming =
              processExternalBgpAdvertisementImport(
                  transformedOutgoingRoute,
                  transformedIncomingRouteBuilder,
                  neighbor,
                  ipv4ImportPolicy,
                  _ribExprEvaluator);
        }
      }
      if (acceptIncoming) {
        Bgpv4Route transformedIncomingRoute = transformedIncomingRouteBuilder.build();
        processMergeInEbgpOrIbgpRib(transformedIncomingRoute, ebgp);
        processMergeInBgpRib(transformedIncomingRoute);
      }
    }
  }

  /**
   * Merges the given {@link Bgpv4Route} into {@link #_ebgpv4Rib} or {@link #_ibgpv4Rib}. If {@code
   * ebgp}, updates the eBGP multipath and best path delta builders accordingly. Returns the
   * multipath delta associated with the merge.
   *
   * @param ebgp Whether to merge the route into the eBGP RIB (if false, merges into iBGP RIB)
   */
  private RibDelta<Bgpv4Route> processMergeInEbgpOrIbgpRib(Bgpv4Route route, boolean ebgp) {
    return processMergeOrRemoveInEbgpOrIbgpRib(route, ebgp, true);
  }

  /**
   * Removes the given {@link Bgpv4Route} from {@link #_ebgpv4Rib} or {@link #_ibgpv4Rib}. If {@code
   * ebgp}, updates the eBGP multipath and best path delta builders accordingly. Returns the
   * multipath delta associated with the removal.
   *
   * @param ebgp Whether to remove the route from the eBGP RIB (if false, removes from iBGP RIB)
   */
  private RibDelta<Bgpv4Route> processRemoveInEbgpOrIbgpRib(Bgpv4Route route, boolean ebgp) {
    return processMergeOrRemoveInEbgpOrIbgpRib(route, ebgp, false);
  }

  /**
   * Merges or removes the given {@link Bgpv4Route} from {@link #_ebgpv4Rib} or {@link #_ibgpv4Rib}.
   * If eBGP, updates the eBGP multipath and best path delta builders accordingly. Returns the
   * multipath delta associated with the change.
   *
   * @param ebgp Whether to modify the eBGP RIB (if false, modifies the iBGP RIB)
   * @param merge Whether to merge the given route (if false, removes it)
   */
  private RibDelta<Bgpv4Route> processMergeOrRemoveInEbgpOrIbgpRib(
      Bgpv4Route route, boolean ebgp, boolean merge) {
    if (ebgp) {
      return processMergeOrRemove(
          _ebgpv4Rib, route, _ebgpv4DeltaBuilder, _ebgpv4DeltaBestPathBuilder, merge);
    }
    // No need to track deltas for IBGP
    return merge ? _ibgpv4Rib.mergeRouteGetDelta(route) : _ibgpv4Rib.removeRouteGetDelta(route);
  }

  /**
   * Merges the given {@link Bgpv4Route} into {@link #_bgpv4Rib}, updating its multipath and best
   * path delta builders accordingly. Returns the multipath delta associated with the merge.
   */
  private RibDelta<Bgpv4Route> processMergeInBgpRib(Bgpv4Route route) {
    return processMergeOrRemoveInBgpRib(route, true);
  }

  /**
   * Removes the given {@link Bgpv4Route} from {@link #_bgpv4Rib}, updating its multipath and best
   * path delta builders accordingly. Returns the multipath delta associated with the removal.
   */
  private RibDelta<Bgpv4Route> processRemoveInBgpRib(Bgpv4Route route) {
    return processMergeOrRemoveInBgpRib(route, false);
  }

  /**
   * Merges or removes the given {@link Bgpv4Route} in {@link #_bgpv4Rib}, updating its multipath
   * and best path delta builders accordingly. Returns the multipath delta associated with the
   * change.
   *
   * @param merge Whether to merge the given route (if false, removes it)
   */
  private RibDelta<Bgpv4Route> processMergeOrRemoveInBgpRib(Bgpv4Route route, boolean merge) {
    return processMergeOrRemove(
        _bgpv4Rib, route, _bgpv4DeltaBuilder, _bgpv4DeltaBestPathBuilder, merge);
  }

  /**
   * Merges or removes the given {@link Bgpv4Route} from the given {@link Bgpv4Rib}, updating both
   * the multipath and best path delta builders accordingly. Returns the multipath delta associated
   * with the change.
   *
   * @param merge Whether to merge the given route (if false, removes it)
   */
  private RibDelta<Bgpv4Route> processMergeOrRemove(
      Bgpv4Rib bgpRib,
      Bgpv4Route route,
      RibDelta.Builder<Bgpv4Route> multipathDeltaBuilder,
      RibDelta.Builder<Bgpv4Route> bestPathDeltaBuilder,
      boolean merge) {
    BgpRib.MultipathRibDelta<Bgpv4Route> delta =
        merge
            ? bgpRib.multipathMergeRouteGetDelta(route)
            : bgpRib.multipathRemoveRouteGetDelta(route);
    multipathDeltaBuilder.from(delta.getMultipathDelta());
    bestPathDeltaBuilder.from(delta.getBestPathDelta());
    return delta.getMultipathDelta();
  }

  @VisibleForTesting
  static boolean processExternalBgpAdvertisementImport(
      Bgpv4Route inputRoute,
      Bgpv4Route.Builder outputRouteBuilder,
      BgpActivePeerConfig neighbor,
      RoutingPolicy ipv4ImportPolicy,
      RibExprEvaluator ribExprEvaluator) {
    // concoct a minimal session properties object: helps with route map constructs like
    // next-hop peer-address. note that "tail" is "this node" for routing policy.
    BgpSessionProperties ourSessionProperties =
        (neighbor.getLocalAs() != null
                && !neighbor.getRemoteAsns().isEmpty()
                && neighbor.getLocalIp() != null
                && neighbor.getPeerAddress() != null)
            ? BgpSessionProperties.builder()
                .setLocalAs(neighbor.getLocalAs())
                .setRemoteAs(neighbor.getRemoteAsns().least())
                .setLocalIp(neighbor.getLocalIp())
                .setRemoteIp(neighbor.getPeerAddress())
                .setAddressFamilies(
                    ImmutableSet.of(Type.IPV4_UNICAST)) // the import policy is IPV4 itself
                .build()
            : null;

    // TODO Figure out whether transformedOutgoingRoute ought to have an annotation
    return ipv4ImportPolicy.processBgpRoute(
        inputRoute, outputRouteBuilder, ourSessionProperties, IN, ribExprEvaluator);
  }

  /**
   * Identifies the given external advertisements for this node and saves them. They will be
   * processed at the start of the BGP computation.
   *
   * @param externalAdverts a set of external BGP advertisements
   * @param ipVrfOwners mapping of IPs to their owners in our network
   */
  void stageExternalAdvertisements(
      Set<BgpAdvertisement> externalAdverts, Map<Ip, Map<String, Set<String>>> ipVrfOwners) {
    // Retain only advertisements that are valid, and stage them for processing once we start up.
    _externalAdvertisements =
        externalAdverts.stream()
            .filter(
                advert -> {
                  // If it is not for us, ignore it
                  if (!advert.getDstNode().equals(_hostname)) {
                    return false;
                  }

                  // If we don't own the IP for this advertisement, ignore it
                  Ip dstIp = advert.getDstIp();
                  Map<String, Set<String>> dstIpOwners = ipVrfOwners.get(dstIp);
                  if (dstIpOwners == null || !dstIpOwners.containsKey(_hostname)) {
                    return false;
                  }

                  Ip srcIp = advert.getSrcIp();
                  // TODO: support passive and unnumbered bgp connections
                  BgpPeerConfig neighbor = _process.getActiveNeighbors().get(srcIp);
                  if (neighbor == null) {
                    return false;
                  }

                  if (advert.getType().isEbgp()
                      && advert.getAsPath().containsAs(neighbor.getLocalAs())
                      && !neighbor
                          .getIpv4UnicastAddressFamily()
                          .getAddressFamilyCapabilities()
                          .getAllowLocalAsIn()) {
                    // skip routes containing this peer's AS unless the session is configured to
                    // allow loops.
                    return false;
                  }

                  return true;
                })
            .collect(ImmutableSet.toImmutableSet());
    if (_externalAdvertisements.isEmpty()) {
      _externalAdvertisements = null;
    }
  }

  /**
   * Process EVPN routes that were received on a session in a different VRF, but must be merged into
   * our VRF
   */
  @Nonnull
  private synchronized <B extends EvpnRoute.Builder<B, R>, R extends EvpnRoute<B, R>>
      RibDelta<R> processCrossVrfEvpnRoute(
          RouteAdvertisement<R> routeAdvertisement, Class<R> clazz) {
    // TODO: consider switching return value to BgpDelta to differentiate e/iBGP
    RibDelta<R> delta;
    BgpRib<R> rib = getRib(clazz, RibType.COMBINED);
    if (routeAdvertisement.isWithdrawn()) {
      delta = rib.removeRouteGetDelta(routeAdvertisement.getRoute());
    } else {
      delta = rib.mergeRouteGetDelta(routeAdvertisement.getRoute());
    }
    // Queue up the routes to be merged into our main RIB
    _toMainRib.from(importRibDelta(_evpnRib, delta));
    return delta;
  }

  @Nonnull
  private static BgpSessionProperties getSessionProperties(BgpTopology bgpTopology, EdgeId edge) {
    Optional<BgpSessionProperties> session =
        bgpTopology.getGraph().edgeValue(edge.tail(), edge.head());
    // BGP topology edge guaranteed to exist since the session is established
    assert session.isPresent();
    return session.get();
  }

  int iterationHashCode() {
    // endOfRound gets called before computing iteration hashcode, so delta builders should be
    // empty. We will consider the finalized deltas from the previous round instead.
    assert _ebgpv4DeltaBuilder.isEmpty();
    assert _ebgpv4DeltaBestPathBuilder.isEmpty();
    assert _bgpv4DeltaBuilder.isEmpty();
    assert _bgpv4DeltaBestPathBuilder.isEmpty();
    return Stream.of(
            // RIBs
            _bgpv4Rib.getTypedRoutes(),
            _evpnRib.getTypedRoutes(),
            // Outgoing RIB deltas
            _ebgpv4DeltaPrev,
            _ebgpv4DeltaPrevBestPath,
            _bgpv4DeltaPrev,
            _bgpv4DeltaPrevBestPath,
            // Message queues
            _evpnType3IncomingRoutes,
            // Delta builders
            _evpnDeltaBuilder.build())
        .collect(toOrderedHashCode());
  }

  public void importCrossVrfV4Routes(
      Stream<RouteAdvertisement<Bgpv4Route>> routesToLeak, BgpVrfLeakConfig bgpConfig) {
    @Nullable
    RoutingPolicy policy =
        Optional.ofNullable(bgpConfig.getImportPolicy()).flatMap(_policies::get).orElse(null);
    routesToLeak.forEach(
        ra -> {
          Bgpv4Route route = ra.getRoute();
          LOGGER.trace("Node {}, VRF {}, Leaking bgpv4 route {}", _hostname, _vrfName, route);

          /*
           Once the route is leaked to a new VRF it can become routing again (it could have been
           non-routing after redistribution). Also, update the next hop to be next-vrf
          */
          Bgpv4Route.Builder builder =
              route.toBuilder()
                  .setNonRouting(false)
                  .setNextHop(NextHopVrf.of(bgpConfig.getImportFromVrf()))
                  .addCommunities(bgpConfig.getAttachRouteTargets());
          switch (route.getSrcProtocol()) {
            case AGGREGATE: // local BGP route
            case BGP:
            case IBGP:
              break;
            default:
              builder.setAdmin(bgpConfig.getAdmin()).setWeight(bgpConfig.getWeight());
              break;
          }

          // Process route through import policy, if one exists
          boolean accept = true;
          if (policy != null) {
            accept = policy.processBgpRoute(route, builder, null, IN, _ribExprEvaluator);
          }
          if (accept) {
            Bgpv4Route transformedRoute = builder.build();
            if (ra.isWithdrawn()) {
              processRemoveInEbgpOrIbgpRib(route, route.getProtocol() != RoutingProtocol.IBGP);
              processRemoveInBgpRib(route);
              _importedFromOtherVrfs.remove(transformedRoute);
            } else {
              RibDelta<Bgpv4Route> d =
                  processMergeInEbgpOrIbgpRib(route, route.getProtocol() != RoutingProtocol.IBGP);
              LOGGER.debug("Node {}, VRF {}, route {} leaked", _hostname, _vrfName, d);
              processMergeInBgpRib(transformedRoute);
              _importedFromOtherVrfs.add(transformedRoute);
            }
          } else {
            LOGGER.trace(
                "Node {}, VRF {}, route {} not leaked because policy denied",
                _hostname,
                _vrfName,
                route);
          }
        });
    unstage();
  }

  public void endOfRound() {
    // Delete all the state from the start of a topology round.
    _evpnEdgesWentUp = ImmutableSet.of();
    _unicastEdgesWentUp = ImmutableSet.of();
    // Delete the external state, now that it is no longer needed
    _mainRibPrev = ImmutableSet.of();
    // Main RIB delta for exporting directly from main RIB
    _mainRibDelta = RibDelta.empty();
  }

  /** Record state at beginning of round prior to pulling from neighbors. */
  public void startOfInnerRound() {
    // Take a snapshot of current RIBs so we know to to send to new add-path sessions.
    _bgpv4Prev = _bgpv4Rib.getTypedRoutes();
    _ebgpv4Prev = _ebgpv4Rib.getTypedRoutes();
    // Take a snapshot of best-paths from current RIBs so we know what to send to new non-add-path
    // sessions, aand also so we can tell what ADDs can be sent to neighbors: those that correspond
    // to current valid best paths.
    _bgpv4PrevBestPath = _bgpv4Rib.getBestPathRoutes();
    _ebgpv4PrevBestPath = _ebgpv4Rib.getBestPathRoutes();
  }

  /**
   * Determine what to advertise to neighbors based on previous and current state, and update
   * previous to curruent.
   */
  public void endOfInnerRound() {
    // Take a snapshot of this round's deltas to [additionally] send to add-path sessions.
    _bgpv4DeltaPrev = _bgpv4DeltaBuilder.build();
    _ebgpv4DeltaPrev = _ebgpv4DeltaBuilder.build();
    // Take a snapshot of this round's best path deltas to send to non-add-path sessions.
    _bgpv4DeltaPrevBestPath = _bgpv4DeltaBestPathBuilder.build();
    _ebgpv4DeltaPrevBestPath = _ebgpv4DeltaBestPathBuilder.build();

    // Delete all the internal state for this round that is no longer needed
    _bgpv4DeltaBuilder = RibDelta.builder();
    _ebgpv4DeltaBuilder = RibDelta.builder();
    _bgpv4DeltaBestPathBuilder = RibDelta.builder();
    _ebgpv4DeltaBestPathBuilder = RibDelta.builder();
  }

  /**
   * Return the stream of all {@link BgpPeerConfig peer configurations} that are part of this
   * process
   */
  private static Stream<BgpPeerConfig> getAllPeerConfigs(BgpProcess process) {
    return Streams.concat(
        process.getActiveNeighbors().values().stream(),
        process.getPassiveNeighbors().values().stream(),
        process.getInterfaceNeighbors().values().stream());
  }

  static BgpSessionProperties getBgpSessionProperties(BgpTopology bgpTopology, EdgeId edge) {
    // BGP topology edge guaranteed to exist since the session is established
    Optional<BgpSessionProperties> session =
        bgpTopology.getGraph().edgeValue(edge.tail(), edge.head());
    return session.orElseThrow(
        () -> new IllegalArgumentException(String.format("No BGP edge %s in BGP topology", edge)));
  }

  @Nonnull
  public Ip getRouterId() {
    return _process.getRouterId();
  }

  /** Return all type 3 EVPN routes */
  public Set<EvpnType3Route> getEvpnType3Routes() {
    return _evpnType3Rib.getTypedRoutes();
  }

  /**
   * Message passing method between BGP processes. Take a collection of BGP {@link
   * RouteAdvertisement}s and puts them onto a local queue corresponding to the session between
   * given neighbors.
   */
  private void enqueueEvpnType3Routes(
      @Nonnull EdgeId edgeId, @Nonnull Stream<RouteAdvertisement<EvpnType3Route>> routes) {
    Queue<RouteAdvertisement<EvpnType3Route>> q = _evpnType3IncomingRoutes.get(edgeId);
    assert q != null; // Invariant of the session being up
    routes.forEach(q::add);
  }

  /** Return a BGP routing process for a given {@link BgpPeerConfigId} */
  @Nonnull
  private static BgpRoutingProcess getNeighborBgpProcess(
      BgpPeerConfigId id, Map<String, Node> allNodes) {
    BgpRoutingProcess proc =
        allNodes
            .get(id.getHostname())
            .getVirtualRouterOrThrow(id.getVrfName())
            .getBgpRoutingProcess();
    assert proc != null; // Otherwise our computation is really wrong
    return proc;
  }

  /** Return a BGP routing process for a sibling VRF on our node */
  @Nonnull
  private BgpRoutingProcess getVrfProcess(String vrf, Map<String, Node> allNodes) {
    BgpRoutingProcess proc =
        allNodes.get(_hostname).getVirtualRouterOrThrow(vrf).getBgpRoutingProcess();
    assert proc != null;
    return proc;
  }

  /** Notifies the process an aggregate has been removed */
  void removeAggregate(AbstractRoute route) {
    // TODO: this is probably busted because it doesn't check e/iBGP ribs.
    BgpRib.MultipathRibDelta<Bgpv4Route> removeDelta = _bgpAggDeps.deleteRoute(route, _bgpv4Rib);
    _bgpv4DeltaBuilder.from(removeDelta.getMultipathDelta());
    _bgpv4DeltaBestPathBuilder.from(removeDelta.getBestPathDelta());
  }

  /**
   * Return a stream of route advertisements we can leak to other VRFs. This includes
   * locally-generated and received routes.
   */
  Stream<RouteAdvertisement<Bgpv4Route>> getRoutesToLeak() {
    return _bgpv4DeltaPrev.getActions().filter(r -> !_importedFromOtherVrfs.contains(r.getRoute()));
  }

  /**
   * Return a set of all bgpv4 routes. Excludes locally-generated (redistributed) routes for now.
   */
  public @Nonnull Set<Bgpv4Route> getV4Routes() {
    return _bgpv4Rib.getTypedRoutes();
  }

  /** Return a set of all bgpv4 backup routes */
  public @Nonnull Set<Bgpv4Route> getV4BackupRoutes() {
    return _bgpv4Rib.getTypedBackupRoutes();
  }

  /** Return a set of all multipath-best evpn routes. */
  public @Nonnull Set<EvpnRoute<?, ?>> getEvpnRoutes() {
    return _evpnRib.getTypedRoutes();
  }

  /** Return a set of all evpn backup routes. */
  public @Nonnull Set<EvpnRoute<?, ?>> getEvpnBackupRoutes() {
    return _evpnRib.getTypedBackupRoutes();
  }

  /** Return a set of all bgpv4 bestpath routes */
  public Set<Bgpv4Route> getBestPathRoutes() {
    return _bgpv4Rib.getBestPathRoutes();
  }

  @Nonnull
  @VisibleForTesting
  Builder<Bgpv4Route> getBgpv4DeltaBuilder() {
    return _bgpv4DeltaBuilder;
  }

  /** Container for eBGP+iBGP RIB deltas */
  private static final class BgpDelta<R extends BgpRoute<?, ?>> {

    private static final BgpDelta<?> EMPTY = new BgpDelta<>(RibDelta.empty(), RibDelta.empty());
    @Nonnull private final RibDelta<R> _ebgpDelta;
    @Nonnull private final RibDelta<R> _ibgpDelta;

    private BgpDelta(@Nonnull RibDelta<R> ebgpDelta, @Nonnull RibDelta<R> ibgpDelta) {
      _ebgpDelta = ebgpDelta;
      _ibgpDelta = ibgpDelta;
    }

    @Nonnull
    public RibDelta<R> getEbgpDelta() {
      return _ebgpDelta;
    }

    @Nonnull
    public RibDelta<R> getIbgpDelta() {
      return _ibgpDelta;
    }

    public boolean isEmpty() {
      return _ebgpDelta.isEmpty() && _ibgpDelta.isEmpty();
    }

    private BgpDelta<R> union(BgpDelta<R> other) {
      return new BgpDelta<>(
          RibDelta.<R>builder().from(_ebgpDelta).from(other._ebgpDelta).build(),
          RibDelta.<R>builder().from(_ibgpDelta).from(other._ibgpDelta).build());
    }

    @SuppressWarnings("unchecked") // fully variant, will never store any Ts
    @Nonnull
    public static <B extends BgpRoute.Builder<B, R>, R extends BgpRoute<B, R>> BgpDelta<R> empty() {
      return (BgpDelta<R>) EMPTY;
    }
  }

  /**
   * Container for a pair for {@link BgpDelta deltas}: one delta for re-advertisement to neighbors,
   * another for merging into local RIBs.
   */
  private static final class DeltaPair<R extends BgpRoute<?, ?>> {
    private static final DeltaPair<?> EMPTY =
        new DeltaPair<Bgpv4Route>(BgpDelta.empty(), BgpDelta.empty());
    @Nonnull private final BgpDelta<R> _toAdvertise;
    @Nonnull private final BgpDelta<R> _toMerge;

    private DeltaPair(BgpDelta<R> toAdvertise, BgpDelta<R> toMerge) {
      _toAdvertise = toAdvertise;
      _toMerge = toMerge;
    }

    @Nonnull
    private DeltaPair<R> union(DeltaPair<R> other) {
      return new DeltaPair<>(
          _toAdvertise.union(other._toAdvertise), _toMerge.union(other._toMerge));
    }

    @SuppressWarnings("unchecked") // fully variant, will never store any Ts
    @Nonnull
    private static <B extends BgpRoute.Builder<B, R>, R extends BgpRoute<B, R>>
        DeltaPair<R> empty() {
      return (DeltaPair<R>) EMPTY;
    }
  }

  /** Type of BGP RIB. Solely for use in {@link BgpRoutingProcess#getRib} */
  private enum RibType {
    /** For eBGP routes only */
    EBGP,
    /** For iBGP routes only */
    IBGP,
    /** Combined RIB, for both eBGP and iBGP routes */
    COMBINED
  }

  /** Return a RIB based on route type and {@link RibType} */
  @SuppressWarnings("unchecked")
  private <B extends BgpRoute.Builder<B, R>, R extends BgpRoute<B, R>, T extends BgpRib<R>>
      T getRib(Class<R> clazz, RibType type) {
    if (clazz.equals(Bgpv4Route.class)) {
      switch (type) {
        case EBGP:
          return (T) _ebgpv4Rib;
        case IBGP:
          return (T) _ibgpv4Rib;
        case COMBINED:
          return (T) _bgpv4Rib;
        default:
          throw new IllegalArgumentException("Unsupported RIB type: " + type);
      }
    } else if (clazz.equals(EvpnType3Route.class)) {
      switch (type) {
        case EBGP:
          return (T) _ebgpType3EvpnRib;
        case IBGP:
          return (T) _ibgpType3EvpnRib;
        case COMBINED:
          return (T) _evpnType3Rib;
        default:
          throw new IllegalArgumentException("Unsupported RIB type: " + type);
      }
    } else if (clazz.equals(EvpnType5Route.class)) {
      switch (type) {
        case EBGP:
          return (T) _ebgpType5EvpnRib;
        case IBGP:
          return (T) _ibgpType5EvpnRib;
        case COMBINED:
          return (T) _evpnType5Rib;
        default:
          throw new IllegalArgumentException("Unsupported RIB type: " + type);
      }
    } else {
      throw new IllegalArgumentException("Unsupported BGP route type: " + clazz.getCanonicalName());
    }
  }
}
