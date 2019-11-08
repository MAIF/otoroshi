const path = require('path');
const TerserJSPlugin = require('terser-webpack-plugin');
const MiniCssExtractPlugin = require('mini-css-extract-plugin');
const OptimizeCSSAssetsPlugin = require('optimize-css-assets-webpack-plugin');
const BundleAnalyzerPlugin = require('webpack-bundle-analyzer').BundleAnalyzerPlugin;

module.exports = (env, argv) => {
  const isProd = argv.mode === 'production' || process.env.NODE_ENV === 'production';
  const config = {
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
      disableHostCheck: true,
      https: process.env.DEV_SERVER_HTTPS ? true : false,
      port: process.env.DEV_SERVER_PORT || 3040,
    },
    module: {
      rules: [{
          test: /\.js$/,
          loaders: ['babel-loader'],
          include: [
            path.resolve(__dirname, 'src'),
            path.resolve(__dirname, 'node_modules/set-value'),
            path.resolve(__dirname, 'node_modules/get-value')
          ]
        },
        {
          test: /\.css$/,
          use: [{
              loader: MiniCssExtractPlugin.loader,
            },
            'css-loader'
          ]
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
          loader: 'url-loader?limit=1&name=[name]/.[ext]'
        },
        {
          test: /\.eot(\?v=\d+\.\d+\.\d+)?$/,
          loader: 'url-loader?limit=1&name=[name]/[name].[ext]'
        },
        {
          test: /\.woff(\?v=\d+\.\d+\.\d+)?$/,
          loader: 'url-loader?limit=10000&mimetype=application/font-woff&name=[name]/[name].[ext]'
        },
        {
          test: /\.woff2(\?v=\d+\.\d+\.\d+)?$/,
          loader: 'url-loader?limit=1&name=[name]/[name].[ext]'
        },
        {
          test: /\.ttf(\?v=\d+\.\d+\.\d+)?$/,
          loader: 'url-loader?limit=1&name=[name]/[name].[ext]'
        },
        {
          test: /\.gif$/,
          loader: 'url-loader?limit=1&name=[name]/[name].[ext]'
        },
        {
          test: /\.svg(\?v=\d+\.\d+\.\d+)?$/,
          loader: 'url-loader?limit=1&name=[name]/[name].[ext]'
        },
        {
          test: /\.png$/,
          loader: 'url-loader?limit=1&name=[name]/[name].[ext]'
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
  };
  if (isProd) {
    return { 
      ...config, 
      optimization: {
        minimize: true,
        minimizer: [
          new TerserJSPlugin({
            parallel: true,
            cache: true
          }),
          new OptimizeCSSAssetsPlugin({})
        ],
      }
    };
  } else {
    return config;
  }
};