const path = require('path');
const TerserJSPlugin = require('terser-webpack-plugin');
const MiniCssExtractPlugin = require('mini-css-extract-plugin');
const OptimizeCSSAssetsPlugin = require('optimize-css-assets-webpack-plugin');
const BundleAnalyzerPlugin = require('webpack-bundle-analyzer').BundleAnalyzerPlugin;
const CompressionPlugin = require("compression-webpack-plugin");

module.exports = (env, argv) => {
  const isProd = argv.mode === 'production' || process.env.NODE_ENV === 'production';
  const config = {
    mode: isProd ? 'production' : 'development',
    entry: {
      backoffice: path.resolve(__dirname, 'src/backoffice.js'),
      genericlogin: path.resolve(__dirname, 'src/genericlogin.js'),
    },
    output: {
      filename: '[name].js',
      path: path.resolve(__dirname, '../public/javascripts/bundle/'),
      publicPath: '/assets/javascripts/bundle/',
      library: 'Otoroshi',
      libraryTarget: 'umd'
    },
    devServer: {
      hot: true,
      allowedHosts: "all",
      https: process.env.DEV_SERVER_HTTPS ? true : false,
      port: process.env.DEV_SERVER_PORT || 3040,
    },
    module: {
      rules: [
        {
          type: 'javascript/auto',
          test: /\.mjs$/,
          use: [],
          include: /node_modules/,
          exclude: /\.(ts|d\.ts|d\.ts\.map)$/,
        },
        {
          test: /\.(js|jsx|ts|tsx)$/,
          use: ['babel-loader'],
          include: [
            path.resolve(__dirname, 'src'),
            // path.resolve(__dirname, 'node_modules', 'yaml'),
            path.resolve(__dirname, 'node_modules/set-value'),
            path.resolve(__dirname, 'node_modules/get-value'),
            path.resolve(__dirname, 'node_modules/graphiql'),
            path.resolve(__dirname, 'node_modules/graphql'),
          ],
          exclude: /\.(d\.ts|d\.ts\.map|spec\.tsx)$/,
        },
        {
          test: /\.css$/,
          use: [MiniCssExtractPlugin.loader, 'css-loader']
        },
        {
          test: /\.scss$/,
          use: [
            'style-loader', // creates style nodes from JS strings
            'css-loader', // translates CSS into CommonJS
            'sass-loader' // compiles Sass to CSS, using Node Sass by default
          ]
        },
        {
          test: /\.less$/,
          use: [
            'style-loader', // creates style nodes from JS strings
            'css-loader', // translates CSS into CommonJS
            'less-loader' // compiles less to CSS, using less by default
          ]
        },
        {
          test: /\.gif$/,
          use: [
            {
              loader: 'url-loader',
              options: {
                limit: 1,
              },
            },
          ]
        },
        {
          test: /\.eot(\?v=\d+\.\d+\.\d+)?$/,
          use: [
            {
              loader: 'url-loader',
              options: {
                limit: 1,
              },
            },
          ]
        },
        {
          test: /\.woff(\?v=\d+\.\d+\.\d+)?$/,
          use: [
            {
              loader: 'url-loader',
              options: {
                limit: 1,
                mimetype: 'application/font-woff'
              },
            },
          ]
        },
        {
          test: /\.woff2(\?v=\d+\.\d+\.\d+)?$/,
          use: [
            {
              loader: 'url-loader',
              options: {
                limit: 1,
              },
            },
          ]
        },
        {
          test: /\.ttf(\?v=\d+\.\d+\.\d+)?$/,
          use: [
            {
              loader: 'url-loader',
              options: {
                limit: 1,
              },
            },
          ]
        },
        {
          test: /\.gif$/,
          use: [
            {
              loader: 'url-loader',
              options: {
                limit: 1,
              },
            },
          ]
        },
        {
          test: /\.svg(\?v=\d+\.\d+\.\d+)?$/,
          use: [
            {
              loader: 'url-loader',
              options: {
                limit: 1,
              },
            },
          ]
        },
        {
          test: /\.png$/,
          use: [
            {
              loader: 'url-loader',
              options: {
                limit: 1,
              },
            },
          ]
        },
      ]
    },
    plugins: [
      // new BundleAnalyzerPlugin(),
      new MiniCssExtractPlugin({
        filename: '[name].css',
        chunkFilename: '[id].css'
      }),
    ],
    resolve: {
      fallback: {
        crypto: require.resolve("crypto-browserify"),
        stream: require.resolve("stream-browserify")
      }
    }
  };
  if (isProd) {
    return {
      ...config,
      plugins: [
        ...config.plugins,
        new CompressionPlugin()
      ],
      optimization: {
        minimize: true,
        minimizer: [
          new TerserJSPlugin({
            parallel: true
          }),
          new OptimizeCSSAssetsPlugin({})
        ],
      }
    };
  } else {
    return config;
  }
};