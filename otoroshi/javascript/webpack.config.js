const path = require('path');
const webpack = require('webpack');
const ExtractTextPlugin = require('extract-text-webpack-plugin');

const resolve = path.resolve;

const isProd = process.env.NODE_ENV === 'production';
const sourcePath = resolve(__dirname, 'src');

const devPlugins = [
  new webpack.optimize.ModuleConcatenationPlugin(),
  new webpack.DefinePlugin({
    '__DEV__': true,
    'process.env': { NODE_ENV: JSON.stringify('dev') }
  }),
  new webpack.NamedModulesPlugin(),
  new webpack.HotModuleReplacementPlugin(),
];
const prodPlugins = [
  new webpack.optimize.ModuleConcatenationPlugin(),
  new webpack.optimize.OccurrenceOrderPlugin(),
  new webpack.LoaderOptionsPlugin({
    minimize: true,
    debug: false
  }),
  new webpack.optimize.UglifyJsPlugin({
    beautify: false,
    compress: {
      warnings: false,
      screw_ie8: true,
      conditionals: true,
      unused: true,
      comparisons: true,
      sequences: true,
      dead_code: true,
      evaluate: true,
      if_return: true,
      join_vars: true,
    },
    output: {
      comments: false,
    }
  }),
  new webpack.DefinePlugin({
    '__DEV__': false,
    'process.env': { NODE_ENV: JSON.stringify('production') }
  }),
  new ExtractTextPlugin({ filename: 'style.css', disable: false, allChunks: true }),
];

const config = {
  context: sourcePath,
  entry: {
    backoffice: './backoffice.js',
  },
  output: {
    filename: '[name].js',
    path: resolve(__dirname, '../public/javascripts/bundle/'),
    publicPath: '/assets/javascripts/bundle/',
    library: 'Otoroshi',
    libraryTarget: 'umd'
  },
  devServer: {
    hot: true,
    port: process.env.DEV_SERVER_PORT || 3000,
    contentBase: resolve(__dirname)
  },
  module: {
    rules: [
      {
        test: /\.js$/,
        loaders: ['babel-loader'],
        include: [
          path.resolve(__dirname, "src"),
          path.resolve(__dirname, "node_modules/set-value"),
          path.resolve(__dirname, "node_modules/get-value")
        ]
        //exclude: /node_modules/
      },
      {
        test: /\.css|\.scss$/,
        use: [ 'style-loader', 'css-loader', 'sass-loader' ]
      },
      { test: /\.(png|jpg)$/, use: 'url-loader?limit=15000' },
      { test: /\.eot(\?v=\d+.\d+.\d+)?$/, use: 'file-loader' },
      { test: /\.woff(2)?(\?v=[0-9]\.[0-9]\.[0-9])?$/, use: 'url-loader?limit=10000&mimetype=application/font-woff' },
      { test: /\.[ot]tf(\?v=\d+.\d+.\d+)?$/, use: 'url-loader?limit=10000&mimetype=application/octet-stream' },
      { test: /\.svg(\?v=\d+\.\d+\.\d+)?$/, use: 'url-loader?limit=10000&mimetype=image/svg+xml' }
    ]
  },
  plugins: isProd ? prodPlugins : devPlugins,
  resolve: {
    extensions: ['.js', '.jsx', '.css'],
    modules: [
      resolve(__dirname, 'node_modules'),
      sourcePath
    ]
  },
};

module.exports = config;