import type { CodegenConfig } from '@graphql-codegen/cli'

// Client preset per the guild's guide
// (the-guild.dev/graphql/codegen/docs/guides/react-vue). The schema is the
// contract in /api. src/gql is generated output, never edited.
const config: CodegenConfig = {
  schema: '../api/graphql/*.graphqls',
  documents: ['src/**/*.tsx'],
  ignoreNoDocuments: true,
  generates: {
    './src/gql/': {
      preset: 'client',
      config: {
        // Unions instead of TS enums and `import type`, because the tsconfig
        // sets erasableSyntaxOnly and verbatimModuleSyntax, which reject enum
        // declarations and value imports of types.
        enumsAsTypes: true,
        useTypeImports: true,
      },
    },
  },
}

export default config
