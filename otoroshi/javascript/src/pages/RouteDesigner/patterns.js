export function getPluginsPatterns(plugins, setNodes, addNodes, clearPLugins) {
  return [
    {
      config: {},
      config_flow: [],
      config_schema: {},
      default_config: {},
      description: 'Add all the plugins you would find in a classic service descriptor',
      id: 'cp:otoroshi.next.plugins.ClassicPluginsPattern',
      name: 'Classic plugins',
      on_request: false,
      on_response: false,
      plugin_categories: ['Patterns'],
      plugin_multi_inst: false,
      plugin_steps: [],
      plugin_tags: [],
      plugin_type: 'ng',
      plugin_visibility: 'userland',
      shortcut: () => {
        function findPlugin(id) {
          return plugins.filter((p) => p.id === id)[0];
        }
        setNodes([
          // pre route
          {
            enabled: false,
            ...findPlugin('cp:otoroshi.next.plugins.Redirection'),
            plugin_index: { PreRoute: 0 },
          },
          {
            enabled: true,
            ...findPlugin('cp:otoroshi.next.plugins.ForceHttpsTraffic'),
            plugin_index: { PreRoute: 1 },
          },
          {
            enabled: false,
            ...findPlugin('cp:otoroshi.next.plugins.BuildMode'),
            plugin_index: { PreRoute: 2 },
          },
          {
            enabled: false,
            ...findPlugin('cp:otoroshi.next.plugins.MaintenanceMode'),
            plugin_index: { PreRoute: 3 },
          },
          {
            enabled: false,
            ...findPlugin('cp:otoroshi.next.plugins.Cors'),
            plugin_index: { PreRoute: 4, TransformResponse: 6 },
          },
          // validate access
          {
            enabled: true,
            ...findPlugin('cp:otoroshi.next.plugins.DisableHttp10'),
            plugin_index: { ValidateAccess: 0 },
          },
          {
            enabled: false,
            ...findPlugin('cp:otoroshi.next.plugins.IpAddressAllowedList'),
            plugin_index: { ValidateAccess: 1 },
          },
          {
            enabled: false,
            ...findPlugin('cp:otoroshi.next.plugins.IpAddressBlockList'),
            plugin_index: { ValidateAccess: 2 },
          },
          {
            enabled: false,
            ...findPlugin('cp:otoroshi.next.plugins.ReadOnlyCalls'),
            plugin_index: { ValidateAccess: 3 },
          },
          {
            enabled: false,
            ...findPlugin('cp:otoroshi.next.plugins.ApikeyCalls'),
            plugin_index: { ValidateAccess: 4, MatchRoute: 0, TransformRequest: 7 },
          },
          {
            enabled: false,
            ...findPlugin('cp:otoroshi.next.plugins.JwtVerification'),
            plugin_index: { ValidateAccess: 5, TransformRequest: 6 },
          },
          {
            enabled: false,
            ...findPlugin('cp:otoroshi.next.plugins.AuthModule'),
            plugin_index: { ValidateAccess: 6 },
          },
          {
            enabled: false,
            ...findPlugin('cp:otoroshi.next.plugins.RoutingRestrictions'),
            plugin_index: { ValidateAccess: 7 },
          },
          {
            enabled: false,
            ...findPlugin('cp:otoroshi.next.plugins.HeadersValidation'),
            plugin_index: { ValidateAccess: 8 },
          },
          // TransformRequest
          {
            enabled: false,
            ...findPlugin('cp:otoroshi.next.plugins.AdditionalHeadersIn'),
            plugin_index: { TransformRequest: 0 },
          },
          {
            enabled: false,
            ...findPlugin('cp:otoroshi.next.plugins.MissingHeadersIn'),
            plugin_index: { TransformRequest: 1 },
          },
          {
            enabled: false,
            ...findPlugin('cp:otoroshi.next.plugins.RemoveHeadersIn'),
            plugin_index: { TransformRequest: 2 },
          },
          {
            enabled: true,
            ...findPlugin('cp:otoroshi.next.plugins.OverrideHost'),
            plugin_index: { TransformRequest: 3 },
          },
          {
            enabled: false,
            ...findPlugin('cp:otoroshi.next.plugins.XForwardedHeaders'),
            plugin_index: { TransformRequest: 4 },
          },
          {
            enabled: false,
            ...findPlugin('cp:otoroshi.next.plugins.OtoroshiInfos'),
            plugin_index: { TransformRequest: 5 },
          },
          // TransformResponse
          {
            enabled: false,
            ...findPlugin('cp:otoroshi.next.plugins.AdditionalHeadersOut'),
            plugin_index: { TransformResponse: 0 },
          },
          {
            enabled: false,
            ...findPlugin('cp:otoroshi.next.plugins.MissingHeadersOut'),
            plugin_index: { TransformResponse: 1 },
          },
          {
            enabled: false,
            ...findPlugin('cp:otoroshi.next.plugins.RemoveHeadersOut'),
            plugin_index: { TransformResponse: 2 },
          },
          {
            enabled: false,
            ...findPlugin('cp:otoroshi.next.plugins.SendOtoroshiHeadersBack'),
            plugin_index: { TransformResponse: 3 },
          },
          {
            enabled: false,
            ...findPlugin('cp:otoroshi.next.plugins.OtoroshiChallenge'),
            plugin_index: { TransformResponse: 4, TransformRequest: 8 },
          },
          {
            enabled: false,
            ...findPlugin('cp:otoroshi.next.plugins.GzipResponseCompressor'),
            plugin_index: { TransformResponse: 7 },
          },
        ]);
      },
    },
    {
      config: {},
      config_flow: [],
      config_schema: {},
      default_config: {},
      description: 'Add all the plugins you need to expose an api',
      id: 'cp:otoroshi.next.plugins.ExposeApiPattern',
      name: 'Expose an API',
      on_request: false,
      on_response: false,
      plugin_categories: ['Patterns'],
      plugin_multi_inst: false,
      plugin_steps: [],
      plugin_tags: [],
      plugin_type: 'ng',
      plugin_visibility: 'userland',
      shortcut: () => {
        function findPlugin(id) {
          return plugins.filter((p) => p.id === id)[0];
        }
        setNodes([
          // pre route
          {
            enabled: true,
            ...findPlugin('cp:otoroshi.next.plugins.ForceHttpsTraffic'),
            plugin_index: { PreRoute: 1 },
          },
          {
            enabled: false,
            ...findPlugin('cp:otoroshi.next.plugins.Cors'),
            plugin_index: { PreRoute: 4, TransformResponse: 6 },
          },
          // validate access
          {
            enabled: true,
            ...findPlugin('cp:otoroshi.next.plugins.DisableHttp10'),
            plugin_index: { ValidateAccess: 0 },
          },
          {
            enabled: true,
            ...findPlugin('cp:otoroshi.next.plugins.ApikeyCalls'),
            plugin_index: { ValidateAccess: 4, MatchRoute: 0, TransformRequest: 7 },
          },
          // TransformRequest
          {
            enabled: false,
            ...findPlugin('cp:otoroshi.next.plugins.OverrideHost'),
            plugin_index: { TransformRequest: 3 },
          },
          {
            enabled: true,
            ...findPlugin('cp:otoroshi.next.plugins.XForwardedHeaders'),
            plugin_index: { TransformRequest: 4 },
          },
          {
            enabled: false,
            ...findPlugin('cp:otoroshi.next.plugins.OtoroshiInfos'),
            plugin_index: { TransformRequest: 5 },
          },
          // TransformResponse
          {
            enabled: true,
            ...findPlugin('cp:otoroshi.next.plugins.SendOtoroshiHeadersBack'),
            plugin_index: { TransformResponse: 3 },
          },
          {
            enabled: false,
            ...findPlugin('cp:otoroshi.next.plugins.OtoroshiChallenge'),
            plugin_index: { TransformResponse: 4, TransformRequest: 8 },
          },
        ]);
      },
    },
    {
      config: {},
      config_flow: [],
      config_schema: {},
      default_config: {},
      description: 'Add all the plugins you need to expose a webapp with authentication',
      id: 'cp:otoroshi.next.plugins.AuthWebappPattern',
      name: 'Expose webapp with auth.',
      on_request: false,
      on_response: false,
      plugin_categories: ['Patterns'],
      plugin_multi_inst: false,
      plugin_steps: [],
      plugin_tags: [],
      plugin_type: 'ng',
      plugin_visibility: 'userland',
      shortcut: () => {
        function findPlugin(id) {
          return plugins.filter((p) => p.id === id)[0];
        }
        setNodes([
          // pre route
          {
            enabled: true,
            ...findPlugin('cp:otoroshi.next.plugins.ForceHttpsTraffic'),
            plugin_index: { PreRoute: 1 },
          },
          {
            enabled: false,
            ...findPlugin('cp:otoroshi.next.plugins.BuildMode'),
            plugin_index: { PreRoute: 2 },
          },
          {
            enabled: false,
            ...findPlugin('cp:otoroshi.next.plugins.MaintenanceMode'),
            plugin_index: { PreRoute: 3 },
          },
          // validate access
          {
            enabled: true,
            ...findPlugin('cp:otoroshi.next.plugins.DisableHttp10'),
            plugin_index: { ValidateAccess: 0 },
          },
          {
            enabled: true,
            ...findPlugin('cp:otoroshi.next.plugins.AuthModule'),
            plugin_index: { ValidateAccess: 6 },
          },
          // TransformRequest
          {
            enabled: false,
            ...findPlugin('cp:otoroshi.next.plugins.OverrideHost'),
            plugin_index: { TransformRequest: 3 },
          },
          {
            enabled: true,
            ...findPlugin('cp:otoroshi.next.plugins.OtoroshiInfos'),
            plugin_index: { TransformRequest: 5 },
          },
          // TransformResponse
          {
            enabled: false,
            ...findPlugin('cp:otoroshi.next.plugins.OtoroshiChallenge'),
            plugin_index: { TransformResponse: 4, TransformRequest: 8 },
          },
          {
            enabled: false,
            ...findPlugin('cp:otoroshi.next.plugins.GzipResponseCompressor'),
            plugin_index: { TransformResponse: 7 },
          },
        ]);
      },
    },
    // {
    //   config: {},
    //   config_flow: [],
    //   config_schema: {},
    //   default_config: {},
    //   description: "Clear all plugins",
    //   id: "cp:otoroshi.next.plugins.ClearPluginsPattern",
    //   name: "Clear plugins",
    //   on_request: false,
    //   on_response: false,
    //   plugin_categories: ['Patterns'],
    //   plugin_multi_inst: false,
    //   plugin_steps: [],
    //   plugin_tags: [],
    //   plugin_type: "ng",
    //   plugin_visibility: "userland",
    //   shortcut: () => {
    //     clearPlugins();
    //   }
    // }
  ];
}
